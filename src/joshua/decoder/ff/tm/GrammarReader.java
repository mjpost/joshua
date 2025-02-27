package joshua.decoder.ff.tm;

import java.io.IOException;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

import joshua.corpus.Vocabulary;
import joshua.util.io.LineReader;

/**
 * This is a base class for simple, ASCII line-based grammars that are stored on disk.
 * 
 * @author Juri Ganitkevitch
 * 
 */
public abstract class GrammarReader<R extends Rule> implements Iterable<R>, Iterator<R> {

  protected static String fieldDelimiter;
  protected static String nonTerminalRegEx;
  protected static String nonTerminalCleanRegEx;

  protected static String description;

  protected String fileName;
  protected LineReader reader;
  protected String lookAhead;
  protected int numRulesRead;

  private static final Logger logger = Logger.getLogger(GrammarReader.class.getName());

  // dummy constructor for
  public GrammarReader() {
    this.fileName = null;
  }

  public GrammarReader(String fileName) {
    this.fileName = fileName;
  }

  public void initialize() {
    try {
      this.reader = new LineReader(fileName);
    } catch (IOException e) {
      throw new RuntimeException("Error opening translation model file: " + fileName + "\n"
          + (null != e.getMessage() ? e.getMessage() : "No details available. Sorry."), e);
    }

    System.err.println(String.format("Reading grammar from file %s...", fileName));
    numRulesRead = 0;
    advanceReader();
  }

  // the reader is the iterator itself
  public Iterator<R> iterator() {
    return this;
  }

  /** Unsupported Iterator method. */
  public void remove() throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  public void close() {
    if (null != this.reader) {
      try {
        this.reader.close();
      } catch (IOException e) {
        // FIXME: is this the right logging level?
        if (logger.isLoggable(Level.WARNING))
          logger.info("Error closing grammar file stream: " + this.fileName);
      }
      this.reader = null;
    }
  }

  /**
   * For correct behavior <code>close</code> must be called on every GrammarReader, however this
   * code attempts to avoid resource leaks.
   * 
   * @see joshua.util.io.LineReader
   */
  protected void finalize() throws Throwable {
    if (this.reader != null) {
      logger.severe("Grammar file stream was not closed, this indicates a coding error: "
          + this.fileName);
    }

    this.close();
    super.finalize();
  }

  public boolean hasNext() {
    return lookAhead != null;
  }

  private void advanceReader() {
    try {
      lookAhead = reader.readLine();
      numRulesRead++;
    } catch (IOException e) {
      logger.severe("Error reading grammar from file: " + fileName);
    }
    if (lookAhead == null && reader != null) {
      this.close();
      System.err.println("...done.");
    }
  }

  public R next() {
    String line = lookAhead;
    advanceReader();

    if ((numRulesRead) % 80000 == 0) {
      System.err.println(String.format("%d rules read", numRulesRead));
    } else if ((numRulesRead) % 1000 == 0) {
      System.err.print(".");
    }
    return parseLine(line);
  }

  protected abstract R parseLine(String line);

  // TODO: keep these around or not?
  public abstract String toWords(R rule);

  public abstract String toWordsWithoutFeatureScores(R rule);

  /**
   * Removes square brackets (and index, if present) from nonterminal id 
   * @param tokenID
   * @return cleaned ID
   */
  public static int cleanNonTerminal(int tokenID) {
    // cleans NT of any markup, e.g., [X,1] may becomes [X], depending
    return Vocabulary.id(cleanNonTerminal(Vocabulary.word(tokenID)));
  }

  /**
   * Removes square brackets (and index, if present) from nonterminal id 
   * @param token
   * @return cleaned token
   */
  public static String cleanNonTerminal(String token) {
    // cleans NT of any markup, e.g., [X,1] may becomes [X], depending on nonTerminalCleanRegEx
    return token.replaceAll(nonTerminalCleanRegEx, "");
  }

  public static boolean isNonTerminal(final String word) {
    // checks if word matches NT regex
    return word.matches(nonTerminalRegEx);
  }

  public String getNonTerminalRegEx() {
    return nonTerminalRegEx;
  }

  public String getNonTerminalCleanRegEx() {
    return nonTerminalCleanRegEx;
  }

}
