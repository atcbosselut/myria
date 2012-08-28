package edu.washington.escience.myriad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.builder.HashCodeBuilder;

import com.google.common.primitives.Ints;

/**
 * Container class for a batch of tuples. The goal is to amortize memory management overhead.
 * 
 * @author dhalperi
 * 
 */
public class TupleBatch {
  /** The hard-coded number of tuples in a batch. */
  public static final int BATCH_SIZE = 100;
  /** Class-specific magic number used to generate the hash code. */
  private static final int MAGIC_HASHCODE1 = 243;
  /** Class-specific magic number used to generate the hash code. */
  private static final int MAGIC_HASHCODE2 = 67;

  /** Schema of tuples in this batch. */
  private final Schema schema;
  /** Tuple data stored as columns in this batch. */
  private final List<Column> columns;
  /** Number of tuples in this batch. */
  private final int numTuples;
  /** Which tuples are valid in this batch. */
  private final BitSet validTuples;

  /**
   * Standard immutable TupleBatch constructor. All fields must be populated before creation and
   * cannot be changed.
   * 
   * @param schema schema of the tuples in this batch. Must match columns.
   * @param columns contains the column-stored data. Must match schema.
   * @param numTuples number of tuples in the batch.
   */
  public TupleBatch(final Schema schema, final List<Column> columns, final int numTuples) {
    /* Take the input arguments directly */
    this.schema = Objects.requireNonNull(schema);
    this.columns = Objects.requireNonNull(columns);
    this.numTuples = Objects.requireNonNull(numTuples);
    /* All tuples are valid */
    this.validTuples = new BitSet(BATCH_SIZE);
    validTuples.set(0, numTuples);
  }

  /**
   * Broken-out copy constructor. Shallow copy of the schema, column list, and the number of tuples;
   * deep copy of the valid tuples since that's what we mutate.
   * 
   * @param schema schema of the tuples in this batch. Must match columns.
   * @param columns contains the column-stored data. Must match schema.
   * @param numTuples number of tuples in the batch.
   * @param validTuples BitSet determines which tuples are valid tuples in this batch.
   */
  private TupleBatch(final Schema schema, final List<Column> columns, final int numTuples,
      final BitSet validTuples) {
    /* Take the input arguments directly */
    this.schema = Objects.requireNonNull(schema);
    this.columns = Objects.requireNonNull(columns);
    this.numTuples = Objects.requireNonNull(numTuples);
    this.validTuples = (BitSet) validTuples.clone();
  }

  /**
   * Standard copy constructor. Shallow copy of the schema, column list, and the number of tuples;
   * deep copy of the valid tuples since that's what we mutate.
   * 
   * @param from TupleBatch to duplicate.
   */
  TupleBatch(final TupleBatch from) {
    /* Take the input arguments directly */
    this.schema = from.schema;
    this.columns = from.columns;
    this.numTuples = from.numTuples;
    this.validTuples = (BitSet) from.validTuples.clone();
  }

  /**
   * Helper function to append the specified row into the specified TupleBatchBuffer.
   * 
   * @param row row to append to the buffer.
   * @param buffer buffer the row is appended to.
   */
  final void appendTupleInto(final int row, final TupleBatchBuffer buffer) {
    for (int i = 0; i < numColumns(); ++i) {
      buffer.put(i, columns.get(i).get(row));
    }
  }

  /**
   * Helper function that mutates this TupleBatch to remove all rows that do not match the specified
   * predicate. WARNING: do not use! Use the non-mutating version.
   * 
   * @param column column on which the predicate operates.
   * @param op predicate by which to test rows.
   * @param operand operand to the predicate.
   * @return a new TupleBatch where all rows that do not match the specified predicate have been
   *         removed.
   */
  private TupleBatch applyFilter(final int column, final Predicate.Op op, final Object operand) {
    if (numTuples > 0) {
      Column columnValues = columns.get(column);
      Type columnType = schema.getFieldType(column);
      int nextSet = -1;
      while ((nextSet = validTuples.nextSetBit(nextSet + 1)) >= 0) {
        if (!columnType.filter(op, columnValues, nextSet, operand)) {
          validTuples.clear(nextSet);
        }
      }
    }
    return this;
  }

  /**
   * Returns a new TupleBatch where all rows that do not match the specified predicate have been
   * removed. Makes a shallow copy of the data, possibly resulting in slowed access to the result,
   * but no copies.
   * 
   * Internal implementation of a SELECT statement.
   * 
   * @param column column on which the predicate operates.
   * @param op predicate by which to test rows.
   * @param operand operand to the predicate.
   * @return a new TupleBatch where all rows that do not match the specified predicate have been
   *         removed.
   */
  public final TupleBatch filter(final int column, final Predicate.Op op, final Object operand) {
    TupleBatch ret = new TupleBatch(this);
    return ret.applyFilter(column, op, operand);
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final boolean getBoolean(final int column, final int row) {
    return ((BooleanColumn) columns.get(column)).getBoolean(row);
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final double getDouble(final int column, final int row) {
    return ((DoubleColumn) columns.get(column)).getDouble(row);
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final float getFloat(final int column, final int row) {
    return ((FloatColumn) columns.get(column)).getFloat(row);
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final int getInt(final int column, final int row) {
    return ((IntColumn) columns.get(column)).getInt(row);
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final long getLong(final int column, final int row) {
    return ((LongColumn) columns.get(column)).getLong(row);
  }

  /**
   * Returns the Schema of the tuples in this batch.
   * 
   * @return the Schema of the tuples in this batch.
   */
  public final Schema getSchema() {
    return schema;
  }

  /**
   * Returns the element at the specified column and row position.
   * 
   * @param column column in which the element is stored.
   * @param row row in which the element is stored.
   * @return the element at the specified position in this TupleBatch.
   */
  public final String getString(final int column, final int row) {
    return ((StringColumn) columns.get(column)).getString(row);
  }

  /**
   * Returns the hash code for the specified tuple using the specified key columns.
   * 
   * @param row row of tuple to hash.
   * @param hashColumns key columns for the hash.
   * @return the hash code value for the specified tuple using the specified key columns.
   */
  private int hashCode(final int row, final int[] hashColumns) {
    /*
     * From
     * http://commons.apache.org/lang/api-2.4/org/apache/commons/lang/builder/HashCodeBuilder.html:
     * 
     * You pick a hard-coded, randomly chosen, non-zero, odd number ideally different for each
     * class.
     */
    HashCodeBuilder hb = new HashCodeBuilder(MAGIC_HASHCODE1, MAGIC_HASHCODE2);
    for (int i : hashColumns) {
      hb.append(columns.get(i).get(row));
    }
    return hb.toHashCode();
  }

  /**
   * The number of columns in this TupleBatch.
   * 
   * @return number of columns in this TupleBatch.
   */
  public final int numColumns() {
    return schema.numFields();
  }

  // public final TupleBatch[] partition(final PartitionFunction<?, ?> p) {
  // return null;
  // }

  /**
   * Hash the valid tuples in this batch and partition them into the supplied TupleBatchBuffers.
   * This is a useful helper primitive for, e.g., the Scatter operator.
   * 
   * @param destinations TupleBatchBuffers into which these tuples will be partitioned.
   * @param hashColumns determines the key columns for the hash.
   */
  final void partitionInto(final TupleBatchBuffer[] destinations, final int[] hashColumns) {
    for (int i : validTupleIndices()) {
      appendTupleInto(i, destinations[hashCode(i, hashColumns)]);
    }
  }

  /**
   * Creates a new TupleBatch with only the indicated columns.
   * 
   * Internal implementation of a (non-duplicate-eliminating) PROJECT statement.
   * 
   * @param remainingColumns zero-indexed array of columns to retain.
   * @return a projected TupleBatch.
   */
  public final TupleBatch project(final int[] remainingColumns) {
    List<Column> newColumns = new ArrayList<Column>();
    Type[] newTypes = new Type[remainingColumns.length];
    String[] newNames = new String[remainingColumns.length];
    int count = 0;
    for (int i : remainingColumns) {
      newColumns.add(columns.get(i));
      newTypes[count] = schema.getFieldType(remainingColumns[count]);
      newNames[count] = schema.getFieldName(remainingColumns[count]);
      count++;
    }
    return new TupleBatch(new Schema(newTypes, newNames), newColumns, numTuples, validTuples);
  }

  /**
   * Creates a new TupleBatch with only the indicated columns.
   * 
   * Internal implementation of a (non-duplicate-eliminating) PROJECT statement.
   * 
   * @param remainingColumns zero-indexed array of columns to retain.
   * @return a projected TupleBatch.
   */
  public final TupleBatch project(final Integer[] remainingColumns) {
    return project(Ints.toArray(Arrays.asList(remainingColumns)));
  }

  @Override
  public final String toString() {
    Type[] columnTypes = schema.getTypes();

    StringBuilder sb = new StringBuilder();
    for (int i = validTuples.nextSetBit(0); i >= 0; i = validTuples.nextSetBit(i + 1)) {
      sb.append("|\t");
      for (int j = 0; j < schema.numFields(); j++) {
        sb.append(columnTypes[j].toString(columns.get(j), i));
        sb.append("\t|\t");
      }
      sb.append("\n");
    }
    return sb.toString();

  }

  /**
   * For the representation with a BitSet listing which rows are valid, generate and return an array
   * containing the indices of all valid rows.
   * 
   * @return an array containing the indices of all valid rows.
   */
  public final int[] validTupleIndices() {
    int[] validT = new int[validTuples.cardinality()];
    int j = 0;
    for (int i = validTuples.nextSetBit(0); i >= 0; i = validTuples.nextSetBit(i + 1)) {
      // operate on index i here
      validT[j++] = i;
    }
    return validT;
  }
}