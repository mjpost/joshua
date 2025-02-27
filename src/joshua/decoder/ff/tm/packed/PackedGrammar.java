package joshua.decoder.ff.tm.packed;

/***
 * This package implements Joshua's packed grammar structure, which enables the efficient loading
 * and accessing of grammars. It is described in the paper:
 * 
 * @article{ganitkevitch2012joshua,
 *   Author = {Ganitkevitch, J. and Cao, Y. and Weese, J. and Post, M. and Callison-Burch, C.},
 *   Journal = {Proceedings of WMT12},
 *   Title = {Joshua 4.0: Packing, PRO, and paraphrases},
 *   Year = {2012}}
 *   
 * The packed grammar works by compiling out the grammar tries into a compact format that is loaded
 * and parsed directly from Java arrays. A fundamental problem is that Java arrays are indexed
 * by ints and not longs, meaning the maximum size of the packed grammar is about 2 GB. This forces
 * the use of packed grammar slices, which together constitute the grammar. The figure in the
 * paper above shows what each slice looks like. 
 * 
 * The division across slices is done in a depth-first manner. Consider the entire grammar organized
 * into a single source-side trie. The splits across tries are done by grouping the root-level
 * outgoing trie arcs --- and the entire trie beneath them --- across slices. 
 * 
 * This presents a problem: if the subtree rooted beneath a single top-level arc is too big for a 
 * slice, the grammar can't be packed. This happens with very large Hiero grammars, for example,
 * where there are a *lot* of rules that start with [X].
 * 
 * A solution being worked on is to split that symbol and pack them into separate grammars with a
 * shared vocabulary, and then rely on Joshua's ability to query multiple grammars for rules to
 * solve this problem. This is not currently implemented but could be done directly in the
 * Grammar Packer.
 */

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

import joshua.corpus.Vocabulary;
import joshua.decoder.JoshuaConfiguration;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.tm.AbstractGrammar;
import joshua.decoder.ff.tm.BasicRuleCollection;
import joshua.decoder.ff.tm.BilingualRule;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.RuleCollection;
import joshua.decoder.ff.tm.Trie;
import joshua.decoder.ff.tm.hash_based.ExtensionIterator;
import joshua.util.encoding.EncoderConfiguration;
import joshua.util.encoding.FloatEncoder;

public class PackedGrammar extends AbstractGrammar {

  private static final Logger logger = Logger.getLogger(PackedGrammar.class.getName());

  private EncoderConfiguration encoding;

  private PackedRoot root;
  private ArrayList<PackedSlice> slices;

  public PackedGrammar(String grammar_dir, int span_limit, String owner,
      JoshuaConfiguration joshuaConfiguration) throws FileNotFoundException, IOException {
    super(joshuaConfiguration);
    this.spanLimit = span_limit;

    // Read the vocabulary.
    logger.info("Reading vocabulary: " + grammar_dir + File.separator + "vocabulary");
    Vocabulary.read(grammar_dir + File.separator + "vocabulary");

    // Read the quantizer setup.
    logger.info("Reading encoder configuration: " + grammar_dir + File.separator + "encoding");
    encoding = new EncoderConfiguration();
    encoding.load(grammar_dir + File.separator + "encoding");

    // Set phrase owner.
    this.owner = Vocabulary.id(owner);

    String[] listing = new File(grammar_dir).list();
    slices = new ArrayList<PackedSlice>();
    for (int i = 0; i < listing.length; i++) {
      if (listing[i].startsWith("slice_") && listing[i].endsWith(".source"))
        slices.add(new PackedSlice(grammar_dir + File.separator + listing[i].substring(0, 11)));
    }

    long count = 0;
    for (PackedSlice s : slices)
      count += s.estimated.length;
    root = new PackedRoot(this);

    logger.info("Loaded " + count + " rules.");
  }

  @Override
  public Trie getTrieRoot() {
    return root;
  }

  @Override
  public boolean hasRuleForSpan(int startIndex, int endIndex, int pathLength) {
    return (spanLimit == -1 || pathLength <= spanLimit);
  }

  @Override
  public int getNumRules() {
    int num_rules = 0;
    for (PackedSlice ps : slices)
      num_rules += ps.featureSize;
    return num_rules;
  }

  public Rule constructManualRule(int lhs, int[] src, int[] tgt, float[] scores, int arity) {
    return null;
  }

  public final class PackedRoot implements Trie {

    private HashMap<Integer, PackedSlice> lookup;

    public PackedRoot(PackedGrammar grammar) {
      lookup = new HashMap<Integer, PackedSlice>();

      for (PackedSlice ps : grammar.slices) {
        int num_children = ps.source[0];
        for (int i = 0; i < num_children; i++)
          lookup.put(ps.source[2 * i + 1], ps);
      }
    }

    @Override
    public Trie match(int word_id) {
      PackedSlice ps = lookup.get(word_id);
      if (ps != null)
        return ps.root().match(word_id);
      return null;
    }

    @Override
    public boolean hasExtensions() {
      return !lookup.isEmpty();
    }

    @Override
    public HashMap<Integer, ? extends Trie> getChildren() {
      HashMap<Integer, Trie> children = new HashMap<Integer, Trie>();
      for (int key : lookup.keySet())
        children.put(key, match(key));
      return children;
    }

    @Override
    public ArrayList<? extends Trie> getExtensions() {
      ArrayList<Trie> tries = new ArrayList<Trie>();
      for (int key : lookup.keySet()) {
        tries.add(match(key));
      }
      return tries;
    }

    @Override
    public boolean hasRules() {
      return false;
    }

    @Override
    public RuleCollection getRuleCollection() {
      return new BasicRuleCollection(0, new int[0]);
    }

    @Override
    public Iterator<Integer> getTerminalExtensionIterator() {
      return new ExtensionIterator(lookup, true);
    }

    @Override
    public Iterator<Integer> getNonterminalExtensionIterator() {
      return new ExtensionIterator(lookup, false);
    }
  }

  public final class PackedSlice {
    private final String name;

    private final int[] source;

    private final int[] target;
    private final int[] targetLookup;

    private MappedByteBuffer features;
    private int featureSize;
    private int[] featureLookup;
    private RandomAccessFile featureFile;

    private float[] estimated;
    private float[] precomputable;
    
    private RandomAccessFile alignmentFile;
    private MappedByteBuffer alignments;
    private int[] alignmentLookup;

    private HashMap<Integer, PackedTrie> tries;

    public PackedSlice(String prefix) throws IOException {
      name = prefix;

      File source_file = new File(prefix + ".source");
      File target_file = new File(prefix + ".target");
      File target_lookup_file = new File(prefix + ".target.lookup");
      File feature_file = new File(prefix + ".features");
      File alignment_file = new File(prefix + ".alignments");

      // Get the channels etc.
      FileInputStream source_fis = new FileInputStream(source_file);
      FileChannel source_channel = source_fis.getChannel();
      int source_size = (int) source_channel.size();

      FileInputStream target_fis = new FileInputStream(target_file);
      FileChannel target_channel = target_fis.getChannel();
      int target_size = (int) target_channel.size();

      featureFile = new RandomAccessFile(feature_file, "r");
      FileChannel feature_channel = featureFile.getChannel();
      int feature_size = (int) feature_channel.size();

      IntBuffer source_buffer = source_channel.map(MapMode.READ_ONLY, 0, source_size).asIntBuffer();
      source = new int[source_size / 4];
      source_buffer.get(source);
      source_fis.close();

      IntBuffer target_buffer = target_channel.map(MapMode.READ_ONLY, 0, target_size).asIntBuffer();
      target = new int[target_size / 4];
      target_buffer.get(target);
      target_fis.close();

      features = feature_channel.map(MapMode.READ_ONLY, 0, feature_size);
      features.load();
      
      if (alignment_file.exists()) {
        alignmentFile = new RandomAccessFile(alignment_file, "r");
        FileChannel alignment_channel = alignmentFile.getChannel();
        int alignment_size = (int) alignment_channel.size();
        alignments = alignment_channel.map(MapMode.READ_ONLY, 0, alignment_size);
        alignments.load();
        
        int num_blocks = alignments.getInt(0);
        alignmentLookup = new int[num_blocks];
        int header_pos = 8;
        for (int i = 0; i < num_blocks; i++) {
          alignmentLookup[i] = alignments.getInt(header_pos);
          header_pos += 4;
        }
      } else {
        alignments = null;
      }

      int num_blocks = features.getInt(0);
      featureLookup = new int[num_blocks];
      estimated = new float[num_blocks];
      precomputable = new float[num_blocks];
      featureSize = features.getInt(4);
      int header_pos = 8;
      for (int i = 0; i < num_blocks; i++) {
        featureLookup[i] = features.getInt(header_pos);
        estimated[i] = Float.NEGATIVE_INFINITY;
        precomputable[i] = Float.NEGATIVE_INFINITY;
        header_pos += 4;
      }

      DataInputStream target_lookup_stream = new DataInputStream(new BufferedInputStream(
          new FileInputStream(target_lookup_file)));
      targetLookup = new int[target_lookup_stream.readInt()];
      for (int i = 0; i < targetLookup.length; i++)
        targetLookup[i] = target_lookup_stream.readInt();
      target_lookup_stream.close();

      tries = new HashMap<Integer, PackedTrie>();
    }

    @SuppressWarnings("unused")
    private final Object guardian = new Object() {
      @Override
      // Finalizer object to ensure feature file handle get closed upon slice's dismissal.
      protected void finalize() throws Throwable {
        featureFile.close();
      }
    };

    private final int[] getTarget(int pointer) {
      // Figure out level.
      int tgt_length = 1;
      while (tgt_length < (targetLookup.length + 1) && targetLookup[tgt_length] <= pointer)
        tgt_length++;
      int[] tgt = new int[tgt_length];
      int index = 0;
      int parent;
      do {
        parent = target[pointer];
        if (parent != -1)
          tgt[index++] = target[pointer + 1];
        pointer = parent;
      } while (pointer != -1);
      return tgt;
    }

    private synchronized PackedTrie getTrie(final int node_address) {
      PackedTrie t = tries.get(node_address);
      if (t == null) {
        t = new PackedTrie(node_address);
        tries.put(node_address, t);
      }
      return t;
    }

    private synchronized PackedTrie getTrie(int node_address, int[] parent_src, int parent_arity,
        int symbol) {
      PackedTrie t = tries.get(node_address);
      if (t == null) {
        t = new PackedTrie(node_address, parent_src, parent_arity, symbol);
        tries.put(node_address, t);
      }
      return t;
    }

    /**
     * NEW VERSION
     * 
     * Returns a string version of the features associated with a rule (represented as a block ID).
     * These features are in the form "feature1=value feature2=value...". By default, unlabeled
     * features are named using the pattern
     * 
     * tm_OWNER_INDEX
     * 
     * where OWNER is the grammar's owner (Vocabulary.word(this.owner)) and INDEX is a 0-based index
     * of the feature found in the grammar.
     * 
     * @param block_id
     * @return
     */

    private final String getFeatures(int block_id) {
      int feature_position = featureLookup[block_id];

      // The number of non-zero features stored with the rule.
      int num_features = encoding.readId(features, feature_position);

      feature_position += EncoderConfiguration.ID_SIZE;
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < num_features; i++) {
        int feature_id = encoding.readId(features, feature_position);
        FloatEncoder encoder = encoding.encoder(feature_id);

        String feature_name = Vocabulary.word(encoding.outerId(feature_id));
        try {
          int index = Integer.parseInt(feature_name);
          sb.append(String.format(" tm_%s_%d=%.5f", Vocabulary.word(owner), index,
              -encoder.read(features, feature_position)));
        } catch (NumberFormatException e) {
          sb.append(String.format(" %s=%.5f", feature_name, -encoder.read(features, feature_position)));
        }

        feature_position += EncoderConfiguration.ID_SIZE + encoder.size();
      }
      return sb.toString().trim();
    }

    private final byte[] getAlignmentArray(int block_id) {
      if (alignments == null)
        throw new RuntimeException("No alignments available.");
      int alignment_position = alignmentLookup[block_id];
      int num_points = (int) alignments.get(alignment_position);
      byte[] alignment = new byte[num_points * 2];
      
      alignments.position(alignment_position + 1);
      alignments.get(alignment, 0, num_points * 2);
      return alignment;
    }
    
    private final PackedTrie root() {
      return getTrie(0);
    }

    public String toString() {
      return name;
    }

    /**
     * A trie node within the grammar slice. Identified by its position within the source array,
     * and, as a supplement, the source string leading from the trie root to the node.
     * 
     * @author jg
     * 
     */
    public class PackedTrie implements Trie, RuleCollection {

      private final int position;

      private boolean sorted = false;

      private int[] src;
      private int arity;

      private PackedTrie(int position) {
        this.position = position;
        src = new int[0];
        arity = 0;
      }

      private PackedTrie(int position, int[] parent_src, int parent_arity, int symbol) {
        this.position = position;
        src = new int[parent_src.length + 1];
        System.arraycopy(parent_src, 0, src, 0, parent_src.length);
        src[src.length - 1] = symbol;
        arity = parent_arity;
        if (Vocabulary.nt(symbol))
          arity++;
      }

      @Override
      public final Trie match(int token_id) {
        int num_children = source[position];
        if (num_children == 0)
          return null;
        if (num_children == 1 && token_id == source[position + 1])
          return getTrie(source[position + 2], src, arity, token_id);
        int top = 0;
        int bottom = num_children - 1;
        while (true) {
          int candidate = (top + bottom) / 2;
          int candidate_position = position + 1 + 2 * candidate;
          int read_token = source[candidate_position];
          if (read_token == token_id) {
            return getTrie(source[candidate_position + 1], src, arity, token_id);
          } else if (top == bottom) {
            return null;
          } else if (read_token > token_id) {
            top = candidate + 1;
          } else {
            bottom = candidate - 1;
          }
          if (bottom < top)
            return null;
        }
      }

      @Override
      public HashMap<Integer, ? extends Trie> getChildren() {
        HashMap<Integer, Trie> children = new HashMap<Integer, Trie>();
        int num_children = source[position];
        for (int i = 0; i < num_children; i++) {
          int symbol = source[position + 1 + 2 * i];
          int address = source[position + 2 + 2 * i];
          children.put(symbol, getTrie(address, src, arity, symbol));
        }
        return children;
      }

      public boolean hasExtensions() {
        return (source[position] != 0);
      }

      public ArrayList<? extends Trie> getExtensions() {
        int num_children = source[position];
        ArrayList<PackedTrie> tries = new ArrayList<PackedTrie>(num_children);

        for (int i = 0; i < num_children; i++) {
          int symbol = source[position + 1 + 2 * i];
          int address = source[position + 2 + 2 * i];
          tries.add(getTrie(address, src, arity, symbol));
        }

        return tries;
      }

      public boolean hasRules() {
        int num_children = source[position];
        return (source[position + 1 + 2 * num_children] != 0);
      }

      public RuleCollection getRuleCollection() {
        return this;
      }

      public List<Rule> getRules() {
        int num_children = source[position];
        int rule_position = position + 2 * (num_children + 1);
        int num_rules = source[rule_position - 1];

        ArrayList<Rule> rules = new ArrayList<Rule>(num_rules);
        for (int i = 0; i < num_rules; i++) {
          rules.add(new PackedRule(rule_position + 3 * i));
        }
        return rules;
      }

      /**
       * We determine if the Trie is sorted by checking if the estimated cost of the first rule in
       * the trie has been set.
       */
      @Override
      public boolean isSorted() {
        return sorted;
      }

      private synchronized void sortRules(List<FeatureFunction> models) {
        int num_children = source[position];
        int rule_position = position + 2 * (num_children + 1);
        int num_rules = source[rule_position - 1];
        if (num_rules == 0) {
          this.sorted = true;
          return;
        }
        Integer[] rules = new Integer[num_rules];

        int target_address;
        int block_id;
        for (int i = 0; i < num_rules; ++i) {
          target_address = source[rule_position + 1 + 3 * i];
          rules[i] = rule_position + 2 + 3 * i;
          block_id = source[rules[i]];

          BilingualRule rule = new BilingualRule(source[rule_position + 3 * i], src,
              getTarget(target_address), getFeatures(block_id), arity, owner);
          estimated[block_id] = rule.estimateRuleCost(models);
          precomputable[block_id] = rule.getPrecomputableCost();
        }

        Arrays.sort(rules, new Comparator<Integer>() {
          public int compare(Integer a, Integer b) {
            float a_cost = estimated[source[a]];
            float b_cost = estimated[source[b]];
            if (a_cost == b_cost)
              return 0;
            return (a_cost > b_cost ? -1 : 1);
          }
        });

        int[] sorted = new int[3 * num_rules];
        int j = 0;
        for (int i = 0; i < rules.length; i++) {
          int address = rules[i];
          sorted[j++] = source[address - 2];
          sorted[j++] = source[address - 1];
          sorted[j++] = source[address];
        }
        for (int i = 0; i < sorted.length; i++)
          source[rule_position + i] = sorted[i];
        this.sorted = true;
      }

      @Override
      public List<Rule> getSortedRules(List<FeatureFunction> featureFunctions) {
        if (!isSorted())
          sortRules(featureFunctions);
        return getRules();
      }

      @Override
      public int[] getSourceSide() {
        return src;
      }

      @Override
      public int getArity() {
        return arity;
      }

      @Override
      public Iterator<Integer> getTerminalExtensionIterator() {
        return new PackedChildIterator(position, true);
      }

      @Override
      public Iterator<Integer> getNonterminalExtensionIterator() {
        return new PackedChildIterator(position, false);
      }

      public final class PackedChildIterator implements Iterator<Integer> {

        private int current;
        private boolean terminal;
        private boolean done;
        private int last;

        PackedChildIterator(int position, boolean terminal) {
          this.terminal = terminal;
          int num_children = source[position];
          done = (num_children == 0);
          if (!done) {
            current = (terminal ? position + 1 : position - 1 + 2 * num_children);
            last = (terminal ? position - 1 + 2 * num_children : position + 1);
          }
        }

        @Override
        public boolean hasNext() {
          if (done)
            return false;
          int next = (terminal ? current + 2 : current - 2);
          if (next == last)
            return false;
          return (terminal ? source[next] > 0 : source[next] < 0);
        }

        @Override
        public Integer next() {
          if (done)
            throw new RuntimeException("No more symbols!");
          int symbol = source[current];
          if (current == last)
            done = true;
          if (!done) {
            current = (terminal ? current + 2 : current - 2);
            done = (terminal ? source[current] < 0 : source[current] > 0);
          }
          return symbol;
        }

        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      }

      public final class PackedRule extends Rule {
        private final int address;

        private int[] tgt = null;
        private FeatureVector features = null;

        public PackedRule(int address) {
          this.address = address;
        }

        @Override
        public void setArity(int arity) {
        }

        @Override
        public int getArity() {
          return PackedTrie.this.getArity();
        }

        @Override
        public void setOwner(int ow) {
        }

        @Override
        public int getOwner() {
          return owner;
        }

        @Override
        public void setLHS(int lhs) {
        }

        @Override
        public int getLHS() {
          return source[address];
        }

        @Override
        public void setEnglish(int[] eng) {
        }

        @Override
        public int[] getEnglish() {
          if (tgt == null) {
            tgt = getTarget(source[address + 1]);
          }
          return tgt;
        }

        @Override
        public void setFrench(int[] french) {
        }

        @Override
        public int[] getFrench() {
          return src;
        }

        @Override
        public FeatureVector getFeatureVector() {
          if (features == null) {
            features = new FeatureVector(getFeatures(source[address + 2]), "");
          }

          return features;
        }
        
        @Override
        public byte[] getAlignment() {
          if (alignments != null)
            return getAlignmentArray(source[address + 2]);
          return null;
        }

        @Override
        public float getEstimatedCost() {
          return estimated[source[address + 2]];
        }

//        @Override
//        public void setPrecomputableCost(float cost) {
//          precomputable[source[address + 2]] = cost;
//        }

        @Override
        public float getPrecomputableCost() {
          return precomputable[source[address + 2]];
        }

        @Override
        public float estimateRuleCost(List<FeatureFunction> models) {
          return estimated[source[address + 2]];
        }

        @Override
        public String toString() {
          StringBuffer sb = new StringBuffer();
          sb.append(Vocabulary.word(this.getLHS()));
          sb.append(" ||| ");
          sb.append(getFrenchWords());
          sb.append(" ||| ");
          sb.append(getEnglishWords());
          sb.append(" |||");
          sb.append(" " + getFeatureVector());
          sb.append(String.format(" ||| %.3f", getEstimatedCost()));
          return sb.toString();
        }
      }
    }
  }

  @Override
  public boolean isRegexpGrammar() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public void addOOVRules(int word, List<FeatureFunction> featureFunctions) {
    throw new RuntimeException("PackedGrammar: I can't add OOV rules");
  }
}
