package joshua.decoder.ff.phrase;

import java.util.List;

import joshua.decoder.chart_parser.SourcePath;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.StatelessFF;
import joshua.decoder.ff.state_maintenance.DPState;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.hypergraph.HGNode;
import joshua.decoder.phrase.Hypothesis;

public class DistortionFF extends StatelessFF {

  public DistortionFF(FeatureVector weights) {
    super(weights, "Distortion");
  }

  @Override
  public DPState compute(Rule rule, List<HGNode> tailNodes, int i, int j, SourcePath sourcePath,
      int sentID, Accumulator acc) {

    if (rule != Hypothesis.BEGIN_RULE && rule != Hypothesis.END_RULE) {
        int start_point = j - rule.getFrench().length + rule.getArity();

        int jump_size = Math.abs(tailNodes.get(0).j - start_point);
        acc.add(name, -jump_size);
    }
    
//    System.err.println(String.format("DISTORTION(%d, %d) from %d = %d", i, j, tailNodes != null ? tailNodes.get(0).j : -1, jump_size));

    return null;
  }
}
