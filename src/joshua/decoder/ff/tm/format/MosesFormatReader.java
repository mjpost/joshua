package joshua.decoder.ff.tm.format;

import joshua.corpus.Vocabulary;
import joshua.decoder.ff.tm.BilingualRule;
import joshua.util.io.LineReader;

/***
 * This class reads in the Moses phrase table format, with support for the source and target side,
 * list of features, and word alignments. It works by simply casting the phrase-based rules to
 * left-branching hierarchical rules and passing them on to its parent class, {@HieroFormatReader}.
 * 
 * There is also a tool to convert the grammars directly, so that they can be suitably packed. Usage:
 * 
 * <pre>
 *     cat PHRASE_TABLE | java -cp $JOSHUA/class joshua.decoder.ff.tm.format.PhraseFormatReader > grammar
 * </pre>
 * 
 * @author Matt Post <post@cs.jhu.edu>
 *
 */

public class MosesFormatReader extends HieroFormatReader {

  private int lhs;

  public MosesFormatReader(String grammarFile) {
    super(grammarFile);
    this.lhs = Vocabulary.id("[X]");
  }
  
  public MosesFormatReader() {
    super();
    this.lhs = Vocabulary.id("[X]");
  }
  
  /**
   * This munges a Moses-style phrase table into a grammar.
   * 
   *    mots francaises ||| French words ||| 1 2 3
   *    
   * becomes
   * 
   *    [X] ||| [X,1] mots francaises ||| [X,1] French words ||| 1 2 3
   * 
   */
  @Override
  public BilingualRule parseLine(String line) {
    String[] fields = line.split(fieldDelimiter);

    int arity = 1;
    
    // foreign side
    String[] foreignWords = fields[0].split("\\s+");
    int[] french = new int[foreignWords.length + 1];
    french[0] = lhs; 
    for (int i = 0; i < foreignWords.length; i++) {
      french[i+1] = Vocabulary.id(foreignWords[i]);
    }

    // English side
    String[] englishWords = fields[1].split("\\s+");
    int[] english = new int[englishWords.length + 1];
    english[0] = -1;
    for (int i = 0; i < englishWords.length; i++) {
      english[i+1] = Vocabulary.id(englishWords[i]);
    }

    // transform feature values
    StringBuffer values = new StringBuffer();
    for (String value: fields[2].split(" ")) {
      float f = Float.parseFloat(value);
      values.append(String.format("%f ", f <= 0.0 ? -100 : -Math.log(f)));
    }
    String sparse_features = values.toString().trim();

    // alignments
    byte[] alignment = null;
    if (fields.length > 3) { // alignments are included
      alignment = readAlignment(fields[3]);
    } else {
      alignment = null;
    }
    
//    System.out.println(String.format("parseLine: %s\n  ->%s", line, sparse_features));

    return new BilingualRule(lhs, french, english, sparse_features, arity, alignment);
  }
  
  /**
   * Converts a Moses phrase table to a Joshua grammar. 
   * 
   * @param args
   */
  public static void main(String[] args) {
    MosesFormatReader reader = new MosesFormatReader();
    for (String line: new LineReader(System.in)) {
      BilingualRule rule = reader.parseLine(line);
      System.out.println(rule.textFormat());
    }    
  }
}
