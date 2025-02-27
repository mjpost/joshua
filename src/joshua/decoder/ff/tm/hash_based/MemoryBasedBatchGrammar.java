package joshua.decoder.ff.tm.hash_based;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import joshua.corpus.Vocabulary;
import joshua.decoder.JoshuaConfiguration;
import joshua.decoder.ff.FeatureFunction;
import joshua.decoder.ff.tm.AbstractGrammar;
import joshua.decoder.ff.tm.BilingualRule;
import joshua.decoder.ff.tm.GrammarReader;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.ff.tm.Trie;
import joshua.decoder.ff.tm.format.HieroFormatReader;
import joshua.decoder.ff.tm.format.MosesFormatReader;
import joshua.decoder.ff.tm.format.SamtFormatReader;

/**
 * This class implements a memory-based bilingual BatchGrammar.
 * <p>
 * The rules are stored in a trie. Each trie node has: (1) RuleBin: a list of rules matching the
 * french sides so far (2) A HashMap of next-layer trie nodes, the next french word used as the key
 * in HashMap
 * 
 * @author Zhifei Li <zhifei.work@gmail.com>
 * @author Matt Post <post@cs.jhu.edu
 */
public class MemoryBasedBatchGrammar extends AbstractGrammar {

  // ===============================================================
  // Instance Fields
  // ===============================================================

  /* The number of rules read. */
  private int qtyRulesRead = 0;

  /* The number of distinct source sides. */
  private int qtyRuleBins = 0;

  /* The trie root. */
  private MemoryBasedTrie root = null;

  /* The file containing the grammar. */
  private String grammarFile;

  private GrammarReader<BilingualRule> modelReader;
  
  /* Maximum source phrase length */
  protected int maxSourcePhraseLength = 0;

  /* Whether the grammar's rules contain regular expressions. */
  private boolean isRegexpGrammar = false;

  // ===============================================================
  // Static Fields
  // ===============================================================

  /** Logger for this class. */
  private static final Logger logger = Logger.getLogger(MemoryBasedBatchGrammar.class.getName());

  // ===============================================================
  // Constructors
  // ===============================================================

  public MemoryBasedBatchGrammar(JoshuaConfiguration joshuaConfiguration) {
    super(joshuaConfiguration);
    this.root = new MemoryBasedTrie();
    this.joshuaConfiguration = joshuaConfiguration;
  }

  public MemoryBasedBatchGrammar(String owner, JoshuaConfiguration joshuaConfiguration) {
    this(joshuaConfiguration);
    this.owner = Vocabulary.id(owner);
  }

  public MemoryBasedBatchGrammar(GrammarReader<BilingualRule> gr,JoshuaConfiguration joshuaConfiguration) {
    // this.defaultOwner = Vocabulary.id(defaultOwner);
    // this.defaultLHS = Vocabulary.id(defaultLHSSymbol);
    this(joshuaConfiguration);
    modelReader = gr;
  }

  public MemoryBasedBatchGrammar(String formatKeyword, String grammarFile, String owner,
      String defaultLHSSymbol, int spanLimit,JoshuaConfiguration joshuaConfiguration) throws IOException {

    this(joshuaConfiguration);
    this.owner = Vocabulary.id(owner);
    Vocabulary.id(defaultLHSSymbol);
    this.spanLimit = spanLimit;
    this.grammarFile = grammarFile;
    this.setRegexpGrammar(formatKeyword.equals("regexp"));
    
    // ==== loading grammar
    this.modelReader = createReader(formatKeyword, grammarFile);
    if (modelReader != null) {
      modelReader.initialize();
      for (BilingualRule rule : modelReader)
        if (rule != null) {
          addRule(rule);
        }
    } else {
      if (logger.isLoggable(Level.WARNING))
        logger.warning("Couldn't create a GrammarReader for file " + grammarFile + " with format "
            + formatKeyword);
    }

    this.printGrammar();
  }

  protected GrammarReader<BilingualRule> createReader(String format, String grammarFile) {

    if (grammarFile != null) {
      if ("hiero".equals(format) || "thrax".equals(format) || "regexp".equals(format)) {
        return new HieroFormatReader(grammarFile);
      } else if ("samt".equals(format)) {
        return new SamtFormatReader(grammarFile);
      } else if ("phrase".equals(format)) {
        return new MosesFormatReader(grammarFile);
      } else {
        throw new RuntimeException(String.format("* FATAL: unknown grammar format '%s'", format));
      }
    }
    return null;
  }


  // ===============================================================
  // Methods
  // ===============================================================

  public void setSpanLimit(int spanLimit) {
    this.spanLimit = spanLimit;
  }
  
  @Override
  public int getNumRules() {
    return this.qtyRulesRead;
  }

  @Override
  public Rule constructManualRule(int lhs, int[] sourceWords, int[] targetWords,
      float[] denseScores, int arity) {
    return null;
  }

  /**
   * if the span covered by the chart bin is greater than the limit, then return false
   */
  public boolean hasRuleForSpan(int i, int j, int pathLength) {
    if (this.spanLimit == -1) { // mono-glue grammar
      return (i == 0);
    } else {
//      System.err.println(String.format("%s HASRULEFORSPAN(%d,%d,%d)/%d = %s", Vocabulary.word(this.owner), i, j, pathLength, spanLimit, pathLength <= this.spanLimit));
      return (pathLength <= this.spanLimit);
    }
  }

  public Trie getTrieRoot() {
    return this.root;
  }


  /**
   * Adds a rule to the grammar
   */
  public void addRule(BilingualRule rule) {

    // TODO: Why two increments?
    this.qtyRulesRead++;

//    if (owner == -1) {
//      System.err.println("* FATAL: MemoryBasedBatchGrammar::addRule(): owner not set for grammar");
//      System.exit(1);
//    }
    rule.setOwner(owner);

    // === identify the position, and insert the trie nodes as necessary
    MemoryBasedTrie pos = root;
    int[] french = rule.getFrench();
    
    maxSourcePhraseLength = Math.max(maxSourcePhraseLength, french.length);
    
    for (int k = 0; k < french.length; k++) {
      int curSymID = french[k];

      if (logger.isLoggable(Level.FINEST)) logger.finest("Matching: " + curSymID);

      /*
       * Note that the nonTerminal symbol in the french is not cleaned (i.e., will be sth like
       * [X,1]), but the symbol in the Trie has to be cleaned, so that the match does not care about
       * the markup (i.e., [X,1] or [X,2] means the same thing, that is X) if
       * (Vocabulary.nt(french[k])) { curSymID = modelReader.cleanNonTerminal(french[k]); if
       * (logger.isLoggable(Level.FINEST)) logger.finest("Amended to: " + curSymID); }
       */

      MemoryBasedTrie nextLayer = (MemoryBasedTrie) pos.match(curSymID);
      if (null == nextLayer) {
        nextLayer = new MemoryBasedTrie();
        if (pos.hasExtensions() == false) {
          pos.childrenTbl = new HashMap<Integer, MemoryBasedTrie>();
        }
        pos.childrenTbl.put(curSymID, nextLayer);
      }
      pos = nextLayer;
    }


    // === add the rule into the trie node
    if (!pos.hasRules()) {
      pos.ruleBin = new MemoryBasedRuleBin(rule.getArity(), rule.getFrench());
      this.qtyRuleBins++;
    }
    pos.ruleBin.addRule(rule);
  }

  protected void printGrammar() {
    logger.info(String.format("MemoryBasedBatchGrammar: Read %d rules with %d distinct source sides from '%s'", this.qtyRulesRead, this.qtyRuleBins, grammarFile));
  }

  /**
   * This returns true if the grammar contains rules that are regular expressions, possibly matching
   * many different inputs.
   * 
   * @return true if the grammar's rules may contain regular expressions.
   */
  @Override
  public boolean isRegexpGrammar() {
    return this.isRegexpGrammar;
  }
  
  public void setRegexpGrammar(boolean value) {
    this.isRegexpGrammar = value;
  }
  
  /**
   * Returns the longest source phrase read.
   * 
   * @return the longest source phrase read (nonterminal + terminal symbols).
   */
  public int getMaxSourcePhraseLength() {
    /* We added a nonterminal to all source sides, so subtract that off. */
    return maxSourcePhraseLength - 1;
  }
  
  /***
   * Takes an input word and creates an OOV rule in the current grammar for that word.
   * 
   * @param sourceWord
   * @param featureFunctions
   */
  public void addOOVRules(int sourceWord, List<FeatureFunction> featureFunctions) {
    
    // TODO: _OOV shouldn't be outright added, since the word might not be OOV for the LM (but now almost
    // certainly is)
    final int targetWord = this.joshuaConfiguration.mark_oovs
        ? Vocabulary.id(Vocabulary.word(sourceWord) + "_OOV")
        : sourceWord;   

    int[] sourceWords = { sourceWord };
    int[] targetWords = { targetWord };
    final byte[] oovAlignment = { 0, 0 };
    
    if (this.joshuaConfiguration.oov_list != null && this.joshuaConfiguration.oov_list.length != 0) {
      for (int i = 0; i < this.joshuaConfiguration.oov_list.length; i++) {
        BilingualRule oovRule = new BilingualRule(
            Vocabulary.id(this.joshuaConfiguration.oov_list[i]), sourceWords, targetWords, "", 0,
            oovAlignment);
        addRule(oovRule);
        oovRule.estimateRuleCost(featureFunctions);
        // System.err.println(String.format("ADDING OOV RULE %s", oovRule));
      }
    } else {
      int nt_i = Vocabulary.id(this.joshuaConfiguration.default_non_terminal);
      BilingualRule oovRule = new BilingualRule(nt_i, sourceWords, targetWords, "", 0,
          oovAlignment);
      addRule(oovRule);
      oovRule.estimateRuleCost(featureFunctions);
    }
  }
}
