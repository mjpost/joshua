package joshua.decoder.ff;

import java.util.List;

import joshua.decoder.chart_parser.SourcePath;
import joshua.decoder.ff.state_maintenance.DPState;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.hypergraph.KBestExtractor.DerivationState;
import joshua.decoder.segment_file.Sentence;

/**
 * Stateless feature functions do not contribute any state. You need not implement this class to
 * create a stateless feature function, but it provides a few convenience functions.
 * 
 * @author Matt Post <post@cs.jhu.edu>
 * @author Juri Ganitkevich <juri@cs.jhu.edu>
 */

public abstract class StatelessFF extends FeatureFunction {

  public StatelessFF(FeatureVector weights, String name) {
    super(weights, name);
  }

  public StatelessFF(FeatureVector weights, String name, String args) {
    super(weights, name, args);
  }

  public final boolean isStateful() {
    return false;
  }

  /**
   * The estimated cost of applying this feature, given only the rule. This is used in sorting the
   * rules for cube pruning. For most features, this will be 0.0.
   */
  public float estimateCost(Rule rule, int sentID) {
    return 0.0f;
  }
  
  @Override
  public final DPState compute(DerivationState derivationState, int i, int j, SourcePath sourcePath,
      Sentence sentence, Accumulator acc) {
    return compute(derivationState.edge.getRule(), derivationState.edge.getTailNodes(), i, j,
        sourcePath, sentence.id(), acc);
  }

  /**
   * Implementations of this should return null, since no state is contributed.
   */
  @Override
  public abstract DPState compute(Rule rule, List<HGNode> tailNodes, int i, int j,
      SourcePath sourcePath, int sentID, Accumulator acc);

  /**
   * Implementations of this should return null, since no state is contributed.
   */
  @Override
  public DPState computeFinal(HGNode tailNode, int i, int j, SourcePath sourcePath, int sentID,
      Accumulator acc) {
    return null;
  }

  /**
   * Stateless functions do not have an estimate of the future cost because they do not have access
   * to the state.
   */
  public final float estimateFutureCost(Rule rule, DPState state, int sentID) {
    return 0.0f;
  }
}
