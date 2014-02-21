package edu.washington.escience.myria.mrbenchmarks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import com.google.common.collect.ImmutableList;

import edu.washington.escience.myria.Schema;
import edu.washington.escience.myria.TupleBatch;
import edu.washington.escience.myria.Type;
import edu.washington.escience.myria.operator.DbQueryScan;
import edu.washington.escience.myria.operator.RootOperator;
import edu.washington.escience.myria.operator.SinkRoot;
import edu.washington.escience.myria.operator.agg.Aggregator;
import edu.washington.escience.myria.operator.agg.SingleGroupByAggregate;
import edu.washington.escience.myria.parallel.CollectConsumer;
import edu.washington.escience.myria.parallel.CollectProducer;
import edu.washington.escience.myria.parallel.ExchangePairID;
import edu.washington.escience.myria.parallel.GenericShuffleConsumer;
import edu.washington.escience.myria.parallel.GenericShuffleProducer;
import edu.washington.escience.myria.parallel.PartitionFunction;
import edu.washington.escience.myria.parallel.SingleFieldHashPartitionFunction;
import edu.washington.escience.myria.parallel.SingleQueryPlanWithArgs;

public class AggregateQueryMonetDB implements QueryPlanGenerator {

  /**
   * .
   */
  private static final long serialVersionUID = -7391407686119481293L;

  final static ImmutableList<Type> outputTypes = ImmutableList.of(Type.STRING_TYPE, Type.DOUBLE_TYPE);
  final static ImmutableList<String> outputColumnNames = ImmutableList.of("sourceIPAddr", "sum_adRevenue");
  final static Schema outputSchema = new Schema(outputTypes, outputColumnNames);

  final ExchangePairID sendToMasterID = ExchangePairID.newID();

  @Override
  public Map<Integer, SingleQueryPlanWithArgs> getWorkerPlan(int[] allWorkers) throws Exception {

    final DbQueryScan localGroupBy =
        new DbQueryScan("select sourceIPAddr, SUM(adRevenue) from UserVisits group by sourceIPAddr", outputSchema);

    final ExchangePairID shuffleLocalGroupByID = ExchangePairID.newID();

    PartitionFunction pf = new SingleFieldHashPartitionFunction(allWorkers.length, 0);

    final GenericShuffleProducer shuffleLocalGroupBy =
        new GenericShuffleProducer(localGroupBy, shuffleLocalGroupByID, allWorkers, pf);
    final GenericShuffleConsumer sc =
        new GenericShuffleConsumer(shuffleLocalGroupBy.getSchema(), shuffleLocalGroupByID, allWorkers);

    final SingleGroupByAggregate agg =
        new SingleGroupByAggregate(sc, new int[] { 1 }, 0, new int[] { Aggregator.AGG_OP_SUM });

    final CollectProducer sendToMaster = new CollectProducer(agg, sendToMasterID, 0);

    final Map<Integer, SingleQueryPlanWithArgs> result = new HashMap<Integer, SingleQueryPlanWithArgs>();
    for (int worker : allWorkers) {
      result.put(worker, new SingleQueryPlanWithArgs(new RootOperator[] { shuffleLocalGroupBy, sendToMaster }));
    }

    return result;
  }

  @Override
  public SinkRoot getMasterPlan(int[] allWorkers, final LinkedBlockingQueue<TupleBatch> receivedTupleBatches) {
    final CollectConsumer serverCollect = new CollectConsumer(outputSchema, sendToMasterID, allWorkers);
    SinkRoot serverPlan = new SinkRoot(serverCollect);
    return serverPlan;
  }
}
