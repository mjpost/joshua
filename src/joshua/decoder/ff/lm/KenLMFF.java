package joshua.decoder.ff.lm;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import joshua.corpus.Vocabulary;
import joshua.decoder.chart_parser.SourcePath;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.lm.kenlm.jni.KenLM;
import joshua.decoder.ff.lm.kenlm.jni.KenLM.StateProbPair;
import joshua.decoder.ff.state_maintenance.DPState;
import joshua.decoder.ff.state_maintenance.KenLMState;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.hypergraph.HGNode;

/**
 * Wrapper for KenLM LMs with left-state minimization. We inherit from the regular
 * 
 * @author Matt Post <post@cs.jhu.edu>
 * @author Juri Ganitkevitch <juri@cs.jhu.edu>
 */
public class KenLMFF extends LanguageModelFF {

  // maps from sentence numbers to KenLM-side pools used to allocate state
  private static final ConcurrentHashMap<Integer, Long> poolMap = new ConcurrentHashMap<Integer, Long>();

  public KenLMFF(FeatureVector weights, String featureName, KenLM lm) {
    super(weights, featureName, lm);
  }

  /**
   * Estimates the cost of a rule. We override here since KenLM can do it more efficiently
   * than the default {@link LanguageModelFF} class.
   *    
   * Most of this function implementation is redundant with compute().
   */
  @Override
  public float estimateCost(Rule rule, int sentID) {
    
    int[] ruleWords = rule.getEnglish();

    // The IDs we'll pass to KenLM
    long[] words = new long[ruleWords.length];

    for (int x = 0; x < ruleWords.length; x++) {
      int id = ruleWords[x];

      if (Vocabulary.nt(id)) {
        // For the estimate, we can just mark negative values
        words[x] = -1;

      } else {
        // Terminal: just add it
        words[x] = id;
      }
    }

    // Get the probability of applying the rule and the new state
    float estimate = weight * ((KenLM) languageModel).estimateRule(words);
//    float parestimate = super.estimateCost(rule, sentID);
//    System.err.println(String.format("KenLM::estimateCost() = %.5f, %.5f", estimate, parestimate));
    return estimate;
  }
  
  /**
   * Computes the features incurred along this edge. Note that these features are unweighted costs
   * of the feature; they are the feature cost, not the model cost, or the inner product of them.
   */
  @Override
  public DPState compute(Rule rule, List<HGNode> tailNodes, int i, int j, SourcePath sourcePath,
      int sentID, Accumulator acc) {

    int[] ruleWords = rule.getEnglish();

    // The IDs we'll pass to KenLM
    long[] words = new long[ruleWords.length];

    for (int x = 0; x < ruleWords.length; x++) {
      int id = ruleWords[x];

      if (Vocabulary.nt(id)) {
        // Nonterminal: retrieve the KenLM long that records the state
        int index = -(id + 1);
        KenLMState state = (KenLMState) tailNodes.get(index).getDPState(stateIndex);
        words[x] = -state.getState();

      } else {
        // Terminal: just add it
        words[x] = id;
      }
    }

    if (!poolMap.containsKey(sentID))
      poolMap.put(sentID, KenLM.createPool());

    // Get the probability of applying the rule and the new state
    StateProbPair pair = ((KenLM) languageModel).probRule(words, poolMap.get(sentID));

    // Record the prob
    acc.add(name, pair.prob);

    // Return the state
    return pair.state;
  }

  /**
   * Destroys the pool created to allocate state for this sentence. Called from the
   * {@link joshua.decoder.Translation} class after outputting the sentence or k-best list. Hosting
   * this map here in KenLMFF statically allows pools to be shared across KenLM instances.
   * 
   * @param sentId
   */
  public void destroyPool(int sentId) {
    if (poolMap.containsKey(sentId))
      KenLM.destroyPool(poolMap.get(sentId));
    poolMap.remove(sentId);
  }

  /**
   * This function differs from regular transitions because we incorporate the cost of incomplete
   * left-hand ngrams, as well as including the start- and end-of-sentence markers (if they were
   * requested when the object was created).
   * 
   * KenLM already includes the prefix probabilities (of shorter n-grams on the left-hand side), so
   * there's nothing that needs to be done.
   */
  @Override
  public DPState computeFinal(HGNode tailNode, int i, int j, SourcePath sourcePath, int sentID,
      Accumulator acc) {

    // KenLMState state = (KenLMState) tailNode.getDPState(getStateIndex());

    // This is unnecessary
    // acc.add(name, 0.0f);

    // The state is the same since no rule was applied
    return new KenLMState();
  }

  /**
   * KenLM probs already include the prefix probabilities (they are substracted out when merging
   * states), so this doesn't need to do anything.
   */
  @Override
  public float estimateFutureCost(Rule rule, DPState currentState, int sentID) {
    return 0.0f;
  }
}
