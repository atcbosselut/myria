package edu.washington.escience.myriad.parallel;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import edu.washington.escience.myriad.DbException;
import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.operator.Operator;
import edu.washington.escience.myriad.table._TupleBatch;

/**
 * The consumer part of the Collect Exchange operator.
 * 
 * A Collect operator collects tuples from all the workers. There is a collect producer on each worker, and a collect
 * consumer on the server and a master worker if a master worker is needed.
 * 
 * The consumer passively collects Tuples from all the paired CollectProducers
 * 
 */
public final class CollectConsumer extends Consumer {

  private static final long serialVersionUID = 1L;

  private final Schema schema;
  private final BitSet workerEOS;
  private final int[] sourceWorkers;
  // private final boolean finish = false;
  private final Map<Integer, Integer> workerIdToIndex;

  /**
   * The child of a CollectConsumer must be a paired CollectProducer.
   */
  private CollectProducer child;

  /**
   * If a child is provided, the TupleDesc is the child's TD
   * 
   * @throws DbException
   */
  public CollectConsumer(final CollectProducer child, final ExchangePairID operatorID, final int[] workerIDs)
      throws DbException {
    super(operatorID);
    this.child = child;
    schema = child.getSchema();
    sourceWorkers = workerIDs;
    workerIdToIndex = new HashMap<Integer, Integer>();
    int idx = 0;
    for (final int w : workerIDs) {
      workerIdToIndex.put(w, idx++);
    }
    workerEOS = new BitSet(workerIDs.length);
  }

  /**
   * If there's no child operator, a TupleDesc is needed
   */
  public CollectConsumer(final Schema schema, final ExchangePairID operatorID, final int[] workerIDs) {
    super(operatorID);
    this.schema = schema;
    sourceWorkers = workerIDs;
    workerIdToIndex = new HashMap<Integer, Integer>();
    int idx = 0;
    for (final int w : workerIDs) {
      workerIdToIndex.put(w, idx++);
    }
    workerEOS = new BitSet(workerIDs.length);
  }

  @Override
  public void cleanup() {
    setInputBuffer(null);
    workerEOS.clear();
  }

  @Override
  protected _TupleBatch fetchNext() throws DbException {
    try {
      return getTuples(true);
    } catch (final InterruptedException e) {
      e.printStackTrace();
      throw new DbException(e.getLocalizedMessage());
    }
  }

  @Override
  public Operator[] getChildren() {
    return new Operator[] { child };
  }

  @Override
  public String getName() {
    return "collect_c";
  }

  @Override
  public Schema getSchema() throws DbException {
    if (child != null) {
      return child.getSchema();
    } else {
      return schema;
    }
  }

  private final _TupleBatch getTuples(boolean blocking) throws InterruptedException {

    int timeToWait = -1;
    if (!blocking) {
      timeToWait = 0;
    }

    ExchangeTupleBatch tb = null;

    while (workerEOS.nextClearBit(0) < sourceWorkers.length) {
      tb = take(timeToWait);
      if (tb != null) {
        if (tb.isEos()) {
          workerEOS.set(workerIdToIndex.get(tb.getWorkerID()));
        } else {
          return tb.getRealData();
        }
      } else {
        return null;
      }
    }
    // have received all the eos message from all the workers
    // finish = true;
    setEOS();
    return null;
  }

  @Override
  public void init() throws DbException {
  }

  @Override
  public void setChildren(final Operator[] children) {
    child = (CollectProducer) children[0];
  }

  @Override
  public _TupleBatch fetchNextReady() throws DbException {
    if (!eos()) {
      try {
        return getTuples(false);
      } catch (final InterruptedException e) {
        e.printStackTrace();
        Thread.currentThread().interrupt();
        throw new DbException(e.getLocalizedMessage());
      }
    }
    return null;

  }
}
