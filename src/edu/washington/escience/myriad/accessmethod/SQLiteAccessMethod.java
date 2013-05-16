package edu.washington.escience.myriad.accessmethod;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;

import edu.washington.escience.myriad.DbException;
import edu.washington.escience.myriad.Schema;
import edu.washington.escience.myriad.TupleBatch;
import edu.washington.escience.myriad.column.Column;
import edu.washington.escience.myriad.column.ColumnFactory;

/**
 * Access method for a SQLite database. Exposes data as TupleBatches.
 * 
 * @author dhalperi
 * 
 */
public final class SQLiteAccessMethod implements AccessMethod {

  /** Default busy timeout is one second. */
  private static final long DEFAULT_BUSY_TIMEOUT = 1000;
  /** The logger for this class. */
  private static final Logger LOGGER = LoggerFactory.getLogger(SQLiteAccessMethod.class);
  /** The database connection **/
  private SQLiteConnection sqliteConnection;
  /** The database schema **/
  private Schema schema;
  /** The connection information **/
  private SQLiteInfo sqliteInfo;
  /** Flag that identifies the connection type (read-only or not) **/
  private Boolean readOnly;

  /**
   * Wrap boolean values as int values since SQLite does not support boolean natively. 
   * This function converts true to 1 and false to 0.
   * 
   * @param b boolean to be converted to SQLite int
   * @return 1 if b is true; 0 if b is false
   */
  static int sqliteBooleanToInt(final boolean b) {
    if (b) {
      return 1;
    }
    return 0;
  }

  /**
   * The constructor. Creates an object and connects with the database
   * 
   * @param sqliteInfo connection information
   * @param schema the database schema
   * @param readOnly whether read-only connection or not
   * @throws DbException if there is an error making the connection.
   */
  public SQLiteAccessMethod(final SQLiteInfo sqliteInfo, final Schema schema, final Boolean readOnly)
      throws DbException {
    Objects.requireNonNull(sqliteInfo);

    this.sqliteInfo = sqliteInfo;
    this.schema = schema;
    this.readOnly = readOnly;
    connect(sqliteInfo, readOnly);
  }

  /**
   * Connects with the database
   * 
   * @param connectionInfo connection information
   * @param readOnly whether read-only connection or not
   * @throws DbException if there is an error making the connection.
   */
  @Override
  public void connect(final ConnectionInfo connectionInfo, final Boolean readOnly) throws DbException {
    Objects.requireNonNull(connectionInfo);

    this.readOnly = readOnly;
    sqliteInfo = (SQLiteInfo) connectionInfo;
    sqliteConnection = null;
    try {
      sqliteConnection = new SQLiteConnection(new File(connectionInfo.getDatabase()));
      if (readOnly) {
        sqliteConnection.openReadonly();
      } else {
        sqliteConnection.open(false);
      }
      sqliteConnection.setBusyTimeout(SQLiteAccessMethod.DEFAULT_BUSY_TIMEOUT);
    } catch (final SQLiteException e) {
      LOGGER.error(e.getMessage());
      throw new DbException(e.getMessage());
    }
  }

  /**
   * Sets the connection to be read-only or writable
   * 
   * @param readOnly whether read-only connection or not
   * @throws DbException if there is an error making the connection.
   */
  @Override
  public void setReadOnly(final Boolean readOnly) throws DbException {
    Objects.requireNonNull(sqliteConnection);
    Objects.requireNonNull(sqliteInfo);

    if (this.readOnly != readOnly) {
      close();
      connect(sqliteInfo, readOnly);
    }
  }

  /**
   * Insert the tuples in this TupleBatch into the database. 
   * 
   * @param insertString the insert statement. 
   * @param tupleBatch the tupleBatch to be inserted
   */
  @Override
  public void tupleBatchInsert(final String insertString, final TupleBatch tupleBatch) throws DbException {
    Objects.requireNonNull(sqliteConnection);

    SQLiteStatement statement = null;
    try {
      /* BEGIN TRANSACTION */
      sqliteConnection.exec("BEGIN TRANSACTION");

      /* Set up and execute the query */
      statement = sqliteConnection.prepare(insertString);
      tupleBatch.getIntoSQLite(statement);

      /* COMMIT TRANSACTION */
      sqliteConnection.exec("COMMIT TRANSACTION");

    } catch (final SQLiteException e) {
      LOGGER.error(e.getMessage());
      throw new DbException(e.getMessage());
    } finally {
      if (statement != null && !statement.isDisposed()) {
        statement.dispose();
      }
    }
  }

  /**
   * Runs a query and expose the results as an Iterator<TupleBatch>.
   * 
   * @param queryString the query
   * @return an Iterator<TupleBatch> containing the results.
   * @throws DbException if there is an error getting tuples.
   */
  @Override
  public Iterator<TupleBatch> tupleBatchIteratorFromQuery(final String queryString) throws DbException {
    Objects.requireNonNull(sqliteConnection);
    Objects.requireNonNull(schema);

    /* Set up and execute the query */
    SQLiteStatement statement = null;
    /*
     * prepare() might throw an exception. My understanding is, when a
     * connection starts in WAL mode, it will first acquire an exclusive lock to
     * check if there is -wal file to recover from. Usually the file is empty so
     * the lock is released pretty fast. However if another connection comes
     * during the exclusive lock period, a "database is locked" exception will
     * still be thrown. The following code simply tries to call prepare again.
     */
    boolean conflict = true;
    int count = 0;
    while (conflict) {
      conflict = false;
      try {
        statement = sqliteConnection.prepare(queryString);
      } catch (final SQLiteException e) {
        conflict = true;
        count++;
        if (count >= 1000) {
          LOGGER.error(e.getMessage());
          throw new DbException(e);
        }
        try {
          Thread.sleep(10);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
      }
    }

    try {
      /* Step the statement once so we can figure out the Schema */
      statement.step();
    } catch (final SQLiteException e) {
      LOGGER.error(e.getMessage());
      throw new DbException(e);
    }

    return new SQLiteTupleBatchIterator(statement, schema, sqliteConnection);
  }

  /**
   * Executes a DDL command. 
   * 
   * @param ddlCommand the DDL command
   * @throws DbException if there is an error in the database.
   */
  @Override
  public void execute(final String ddlCommand) throws DbException {
    Objects.requireNonNull(sqliteConnection);

    try {
      sqliteConnection.exec(ddlCommand);
    } catch (final SQLiteException e) {
      LOGGER.error(e.getMessage());
      throw new DbException(e.getMessage());
    }
  }

  /**
   * Closes the database connection.
   * 
   */
  @Override
  public void close() throws DbException {
    if (sqliteConnection != null) {
      sqliteConnection.dispose();
    }
  }

  /**
   * Inserts a TupleBatch into the SQLite database.
   * 
   * @param pathToSQLiteDb filename of the SQLite database
   * @param insertString parameterized string used to insert tuples
   * @param tupleBatch TupleBatch that contains the data to be inserted
   */
  public static synchronized void tupleBatchInsert(final String pathToSQLiteDb, final String insertString,
      final TupleBatch tupleBatch) throws DbException {

    SQLiteAccessMethod sqliteAccessMethod = null;
    try {
      sqliteAccessMethod = new SQLiteAccessMethod(SQLiteInfo.of(pathToSQLiteDb), null, false);
      // sqliteAccessMethod.execute("BEGIN TRANSACTION");
      sqliteAccessMethod.tupleBatchInsert(insertString, tupleBatch);
      // sqliteAccessMethod.execute("COMMIT TRANSACTION");
    } catch (DbException e) {
      throw e;
    } finally {
      if (sqliteAccessMethod != null) {
        sqliteAccessMethod.close();
      }
    }
  }

  /**
   * Create a SQLite Connection and then expose the results as an Iterator<TupleBatch>.
   * 
   * @param pathToSQLiteDb filename of the SQLite database
   * @param queryString string containing the SQLite query to be executed
   * @param schema the Schema describing the format of the TupleBatch containing these results.
   * @return an Iterator<TupleBatch> containing the results of the query
   */
  public static Iterator<TupleBatch> tupleBatchIteratorFromQuery(final String pathToSQLiteDb, final String queryString,
      final Schema schema) throws DbException {

    SQLiteAccessMethod sqliteAccessMethod = null;
    try {
      sqliteAccessMethod = new SQLiteAccessMethod(SQLiteInfo.of(pathToSQLiteDb), schema, true);
      return sqliteAccessMethod.tupleBatchIteratorFromQuery(queryString);
    } catch (DbException e) {
      if (sqliteAccessMethod != null) {
        sqliteAccessMethod.close();
      }
      throw e;
    }
  }

  /** Inaccessible. */
  private SQLiteAccessMethod() {
    throw new AssertionError();
  }

}

/**
 * Wraps a SQLiteStatement result set in a Iterator<TupleBatch>.
 * 
 * @author dhalperi
 * 
 */
class SQLiteTupleBatchIterator implements Iterator<TupleBatch> {
  /** The logger for this class. Uses SQLiteAccessMethod settings. */
  private static final Logger LOGGER = LoggerFactory.getLogger(SQLiteAccessMethod.class);
  /** The results from a SQLite query that will be returned in TupleBatches by this Iterator. */
  private final SQLiteStatement statement;
  /** The connection to the SQLite database. */
  private final SQLiteConnection connection;
  /** The Schema of the TupleBatches returned by this Iterator. */
  private final Schema schema;

  /**
   * Wraps a SQLiteStatement result set in an Iterator<TupleBatch>.
   * 
   * @param statement the SQLiteStatement containing the results.
   * @param schema the Schema describing the format of the TupleBatch containing these results.
   * @param connection the connection to the SQLite database.
   */
  SQLiteTupleBatchIterator(final SQLiteStatement statement, final Schema schema, final SQLiteConnection connection) {
    this.statement = statement;
    this.connection = connection;
    this.schema = schema;
  }

  /**
   * Wraps a SQLiteStatement result set in an Iterator<TupleBatch>.
   * 
   * @param statement the SQLiteStatement containing the results. If it has not yet stepped, this constructor will step
   *          it. Then the Schema of the generated TupleBatchs will be extracted from the statement.
   * @param connection the connection to the SQLite database.
   * @param schema the Schema describing the format of the TupleBatch containing these results.
   */
  SQLiteTupleBatchIterator(final SQLiteStatement statement, final SQLiteConnection connection, final Schema schema) {
    this.connection = connection;
    this.statement = statement;
    try {
      if (!statement.hasStepped()) {
        statement.step();
      }
      this.schema = schema;
    } catch (final SQLiteException e) {
      throw new RuntimeException(e.getMessage());
    }
  }

  @Override
  public boolean hasNext() {
    final boolean hasRow = statement.hasRow();
    if (!hasRow) {
      statement.dispose();
      connection.dispose();
    }
    return hasRow;
  }

  @Override
  public TupleBatch next() {
    /* Allocate TupleBatch parameters */
    final int numFields = schema.numColumns();
    final List<Column<?>> columns = ColumnFactory.allocateColumns(schema);

    /**
     * Loop through resultSet, adding one row at a time. Stop when numTuples hits BATCH_SIZE or there are no more
     * results.
     */
    int numTuples;
    try {
      for (numTuples = 0; numTuples < TupleBatch.BATCH_SIZE && statement.hasRow(); ++numTuples) {
        for (int column = 0; column < numFields; ++column) {
          columns.get(column).putFromSQLite(statement, column);
        }
        statement.step();
      }
    } catch (final SQLiteException e) {
      LOGGER.error("Got SQLiteException:" + e + "in TupleBatchIterator.next()");
      throw new RuntimeException(e.getMessage());
    }

    return new TupleBatch(schema, columns, numTuples);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("SQLiteTupleBatchIterator.remove()");
  }
}