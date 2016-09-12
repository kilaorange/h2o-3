package water.rapids.ast.prims.advmath;

import org.apache.commons.math3.distribution.NormalDistribution;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;
import water.rapids.Env;
import water.rapids.Val;
import water.rapids.ast.AstPrimitive;
import water.rapids.ast.AstRoot;
import water.rapids.ast.params.AstNum;
import water.rapids.ast.params.AstNumList;
import water.rapids.ast.params.AstStr;
import water.rapids.vals.ValFrame;
import water.util.ArrayUtils;

import java.util.ArrayList;


public class AstISax extends AstPrimitive {
  @Override
  public String[] args() { return new String[]{"ary", "numWords", "maxCardinality"}; }

  @Override
  public int nargs() { return 1 + 3; } // (isax x breaks)

  @Override
  public String str() { return "isax"; }

  @Override
  public Val apply(Env env, Env.StackHelp stk, AstRoot asts[]) {
    // stack is [ ..., ary, numWords, maxCardinality]
    // handle the breaks
    Frame fr2;
    Frame f = stk.track(asts[1].exec(env)).getFrame();

    int c = 0;
    for (Vec v : f.vecs()) {
      if (!v.isNumeric()) c++;
    }
    if (c > 0) throw new IllegalArgumentException("iSAX only applies to numeric columns");

    AstRoot n = asts[2];
    AstRoot mc = asts[3];
    int numWords = -1;
    int maxCardinality = -1;

    numWords = (int) n.exec(env).getNum();
    maxCardinality = (int) mc.exec(env).getNum();

    AstISax.ISaxTask isaxt;

    ArrayList<String> columns = new ArrayList<String>();
    for (int i = 0; i < numWords; i++) {
      columns.add("c"+i);
    }
    fr2 = new AstISax.ISaxTask(numWords, maxCardinality)
            .doAll(numWords, Vec.T_NUM, f).outputFrame(null, columns.toArray(new String[numWords]), null);

    return new ValFrame(fr2);
  }


  public static class ISaxTask extends MRTask<AstISax.ISaxTask> {
    public int nw;
    public int mc;
    static NormalDistribution nd;
    static ArrayList<Double> probBoundaries; // for tokenizing iSAX

    ISaxTask(int numWords, int maxCardinality) {
      nw = numWords;
      mc = maxCardinality;
      nd = new NormalDistribution();
      // come up with NormalDist boundaries
      double step = 1.0 / mc;
      probBoundaries = new ArrayList<Double>(); //cumulative dist function boundaries R{0-1}
      for (int i = 0; i < mc; i++) {
        probBoundaries.add(nd.inverseCumulativeProbability(i*step));
      }
    }
    @Override
    public void map(Chunk cs[],NewChunk[] nc) {
      int step = cs.length/nw;
      int chunkSize = cs[0].len();
      int w_i = 0; //word iterator
      double[] seriesSums = new double[chunkSize];
      double[] seriesCounts = new double[chunkSize];
      double[] seriesSSE = new double[chunkSize];
      double[][] chunkMeans = new double[chunkSize][nw];
      // Loop by words in the time series
      for (int i = 0; i < cs.length; i+=step) {
        Chunk subset[] = ArrayUtils.subarray(cs,i,i+step);
        // Loop by each series in the chunk
        for (int j = 0; j < chunkSize; j++) {
          double mySum = 0.0;
          double myCount = 0.0;
          // Loop through all the data in the chunk for the given series in the given subset (word)
          for (Chunk c : subset) {
            if (c != null) {
              // Calculate mean and sigma in one pass
              double oldMean = myCount < 1 ? 0.0 : mySum/myCount;
              mySum += c.atd(j);
              seriesSums[j] += c.atd(j);
              myCount++;
              seriesCounts[j] += 1;
              seriesSSE[j] += (c.atd(j) - oldMean) * (c.atd(j) - mySum/myCount);
            }
          }
          chunkMeans[j][w_i] = mySum / myCount;
        }
        w_i++;
        if (w_i>= nw) break;
      }
      //
      for (int w = 0; w < nw; w++) {
        for (int i = 0; i < chunkSize; i++) {
          double seriesMean = seriesSums[i] / seriesCounts[i];
          double seriesStd = Math.sqrt(seriesSSE[i] / (seriesCounts[i] - 1));
          double zscore = (chunkMeans[i][w] - seriesMean) / seriesStd;
          int p_i = 0;
          while (probBoundaries.get(p_i + 1) < zscore) {
            p_i++;
            if (p_i == mc - 1) break;
          }
          nc[w].addNum(p_i);
        }
      }
    }
  }


}