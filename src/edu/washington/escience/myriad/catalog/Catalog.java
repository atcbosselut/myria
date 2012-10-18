package edu.washington.escience.myriad.catalog;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.almworks.sqlite4java.SQLiteConnection;
import com.almworks.sqlite4java.SQLiteException;
import com.almworks.sqlite4java.SQLiteStatement;

/**
 * This class is intended to store the configuration information for a Myriad installation.
 * 
 * @author dhalperi
 * 
 */
public final class Catalog {
  /** The logger for this class. Defaults to myriad level, but could be set to a finer granularity if needed. */
  private static final Logger LOGGER = LoggerFactory.getLogger("edu.washington.escience.myriad");

  /**
   * @param filename the path to the SQLite database storing the catalog.
   * @param description specifies a description for the configuration stored in this Catalog.
   * @return a fresh Catalog fitting the specified description.
   * @throws IOException if the specified file already exists.
   * @throws CatalogException if there is an error opening the database.
   * 
   *           TODO add some sanity checks to the filename?
   */
  public static Catalog create(final String filename, final String description) throws IOException, CatalogException {
    Objects.requireNonNull(filename);
    Objects.requireNonNull(description);
    return Catalog.create(filename, description, false);
  }

  /**
   * @param filename the path to the SQLite database storing the catalog.
   * @param description specifies a description for the configuration stored in this Catalog.
   * @param overwrite specifies whether to overwrite an existing Catalog.
   * @return a fresh Catalog fitting the specified description.
   * @throws IOException if overwrite is true and the specified file already exists.
   * @throws CatalogException if there is an error opening the database.
   * 
   *           TODO add some sanity checks to the filename?
   */
  public static Catalog create(final String filename, final String description, final boolean overwrite)
      throws IOException, CatalogException {
    Objects.requireNonNull(filename);
    Objects.requireNonNull(description);

    /* if overwrite is false, error if the file exists. */
    File catalogFile = new File(filename);
    if (!overwrite && catalogFile.exists()) {
      throw new IOException(filename + " already exists");
    }
    return Catalog.createFromFile(catalogFile, description, overwrite);
  }

  /**
   * 
   * @param catalogFile a File object pointing to the SQLite database that will store the Catalog. If catalogFile is
   *          null, this creates an in-memory SQLite database.
   * @param description specifies a description for the configuration stored in this Catalog.
   * @param overwrite specifies whether to overwrite an existing Catalog. Ignored if using an in-memory SQLite database.
   * @return a fresh Catalog fitting the specified description.
   * @throws CatalogException if there is an error opening the database.
   * 
   *           TODO add some sanity checks to the filename?
   */
  private static Catalog createFromFile(final File catalogFile, final String description, final boolean overwrite)
      throws CatalogException {
    Objects.requireNonNull(description);

    /* Connect to the database. */
    final SQLiteConnection sqliteConnection = new SQLiteConnection(catalogFile);
    try {
      sqliteConnection.open(overwrite);
    } catch (SQLiteException e) {
      LOGGER.error(e.toString());
      throw new CatalogException("SQLiteException while creating the new Catalog", e);
    }

    /* Create all the tables in the Catalog. */
    try {
      sqliteConnection.exec("CREATE TABLE configuration (key STRING NOT NULL, value STRING NOT NULL);");
      sqliteConnection.exec("CREATE TABLE workers (worker_id INTEGER PRIMARY KEY ASC, host_port STRING);");
      sqliteConnection.exec("CREATE TABLE masters (master_id INTEGER PRIMARY KEY ASC, host_port STRING);");
    } catch (SQLiteException e) {
      LOGGER.error(e.toString());
      throw new CatalogException("SQLiteException while creating new Catalog tables", e);
    }

    /* Populate what tables we can. */
    try {
      SQLiteStatement statement =
          sqliteConnection.prepare("INSERT INTO configuration (key, value) VALUES (?,?);", false);
      statement.bind(1, "description");
      statement.bind(2, description);
      statement.step();
      statement.dispose();
    } catch (SQLiteException e) {
      LOGGER.error(e.toString());
      throw new CatalogException("SQLiteException while populating new Catalog tables", e);
    }

    return new Catalog(sqliteConnection);
  }

  /**
   * @param description specifies a description for the configuration stored in this Catalog.
   * @return a fresh Catalog fitting the specified description.
   * @throws CatalogException if there is an error opening the database.
   * 
   *           TODO add some sanity checks to the filename?
   */
  public static Catalog createInMemory(final String description) throws CatalogException {
    Objects.requireNonNull(description);

    return Catalog.createFromFile(null, description, true /* Ignored */);
  }

  /**
   * Opens the Myriad catalog stored as a SQLite database in the specified file.
   * 
   * @param filename the path to the SQLite database storing the catalog.
   * @return an initialized Catalog object ready to be used for experiments.
   * @throws FileNotFoundException if the given file does not exist.
   * @throws CatalogException if there is an error connecting to the database.
   * 
   *           TODO add some sanity checks to the filename?
   */
  public static Catalog open(final String filename) throws FileNotFoundException, CatalogException {
    Objects.requireNonNull(filename);

    /* See if the file exists, and create it if not. */
    File catalogFile = new File(filename);
    if (!catalogFile.exists()) {
      throw new FileNotFoundException(filename);
    }

    /* Connect to the database */
    SQLiteConnection sqliteConnection = new SQLiteConnection(catalogFile);
    try {
      sqliteConnection.open(false);
    } catch (SQLiteException e) {
      LOGGER.error(e.toString());
      throw new CatalogException(e);
    }

    return new Catalog(sqliteConnection);
  }

  /**
   * The description of the setup specified by this Catalog. For example, this could be "two node local test" or
   * "20-node Greenplum cluster".
   */
  private String description = null;

  /** The connection to the SQLite database that stores the Catalog. */
  private final SQLiteConnection sqliteConnection;

  /**
   * Not publicly accessible.
   * 
   * @param sqliteConnection connection to the SQLite database that stores the Catalog.
   */
  private Catalog(final SQLiteConnection sqliteConnection) {
    this.sqliteConnection = sqliteConnection;
  }

  /**
   * Extract the value of a particular configuration parameter from the database. Returns null if the parameter is not
   * configured.
   * 
   * @param key the name of the configuration parameter.
   * @return the value of the configuration parameter, or null if that configuration is not supported.
   * @throws CatalogException if there is an error in the backing database.
   */
  public String getConfigurationValue(final String key) throws CatalogException {
    try {
      /* Getting this out is a simple query, which does not need to be cached. */
      SQLiteStatement statement =
          sqliteConnection.prepare("SELECT value FROM configuration WHERE key=? LIMIT 1;", false).bind(1, key);
      if (!statement.step()) {
        /* If step() returns false, there's no data. Return null. */
        return null;
      }
      final String ret = statement.columnString(0);
      statement.dispose();
      return ret;
    } catch (SQLiteException e) {
      LOGGER.error(e.toString());
      throw new CatalogException(e);
    }
  }

  /**
   * @return the description of this Catalog.
   * @throws CatalogException if there is an error extracting the description from the database.
   */
  public String getDescription() throws CatalogException {
    /* If we have the answer cached, use it. */
    if (description != null) {
      return description;
    }

    description = getConfigurationValue("description");
    return description;
  }
}
