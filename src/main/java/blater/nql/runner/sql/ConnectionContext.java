package blater.nql.runner.sql;

import blater.nql.domain.SqlType;
import blater.nql.util.Log;

import java.sql.*;
import java.util.*;

/*
 * Responsibility: Opens and owns the active JDBC connection and its
 * connection-local table metadata caches.
 */
public final class ConnectionContext {
  private Connection connection;
  private final Map<String, Map<String, SqlType>> columnTypeCache = new HashMap<>();
  private final Map<String, List<String>> columnNameCache = new HashMap<>();

  public Connection connection() {
    if (connection == null) {
      return Log.fatal(IllegalStateException.class, "Database connection has not been opened.");
    }
    return connection;
  }

  public void connect(Map<String, String> params) {
    close();
    try {
      loadDriver(params);
      connection = DriverManager.getConnection(
          params.get("jdbc.database"),
          params.get("jdbc.username"),
          params.get("jdbc.password"));
    } catch (Exception ex) {
      Log.fatal(IllegalStateException.class, "Could not connect to the database", ex);
    }
    clearCaches();
  }

  private void loadDriver(Map<String, String> params) throws ClassNotFoundException {
    String driver = params.get("jdbc.driver");
    if (driver != null && !driver.isBlank()) {
      switch (driver) {
        case "h2" -> Class.forName("org.h2.Driver");
        case "oracle" -> Class.forName("oracle.jdbc.OracleDriver");
        case "sqlserver" -> Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        case "db2" -> Class.forName("com.ibm.db2.jcc.DB2Driver");
        case "hana" -> Class.forName("com.sap.db.jdbc.Driver");
        case "informix" -> Class.forName("com.informix.jdbc.IfxDriver");
        case "mysql" -> Class.forName("com.mysql.cj.jdbc.Driver");
        case "mariadb" -> Class.forName("org.mariadb.jdbc.Driver");
        case "postgresql" -> Class.forName("org.postgresql.Driver");
        default -> throw new IllegalArgumentException("Unsupported JDBC driver [" + driver + "].");
      }
      return;
    }

    String className = params.get("jdbc.class.name");
    if (className != null && !className.isBlank()) {
      Class.forName(className);
      return;
    }

    String inferredClass = driverClassForUrl(params.get("jdbc.database"));
    if (inferredClass != null) Class.forName(inferredClass);
    // Unknown URLs deliberately fall through to JDBC service discovery. This
    // keeps URL-only custom drivers usable on the JVM while known bundled
    // drivers remain explicit enough for native-image reachability.
  }

  private static String driverClassForUrl(String url) {
    if (url == null) return null;
    if (url.startsWith("jdbc:h2:")) return "org.h2.Driver";
    if (url.startsWith("jdbc:postgresql:")) return "org.postgresql.Driver";
    if (url.startsWith("jdbc:mysql:")) return "com.mysql.cj.jdbc.Driver";
    if (url.startsWith("jdbc:mariadb:")) return "org.mariadb.jdbc.Driver";
    if (url.startsWith("jdbc:oracle:")) return "oracle.jdbc.OracleDriver";
    if (url.startsWith("jdbc:sqlserver:")) return "com.microsoft.sqlserver.jdbc.SQLServerDriver";
    if (url.startsWith("jdbc:db2:")) return "com.ibm.db2.jcc.DB2Driver";
    if (url.startsWith("jdbc:sap:")) return "com.sap.db.jdbc.Driver";
    if (url.startsWith("jdbc:informix-sqli:")) return "com.informix.jdbc.IfxDriver";
    return null;
  }

  public void close() {
    clearCaches();
    if (connection == null)
      return;

    try {
      connection.close();
    } catch (SQLException ex) {
      Log.warn("Could not close database connection: {}", ex.getMessage());
    } finally {
      connection = null;
    }
  }

  Map<String, SqlType> columnTypes(String tableName) {
    loadColumnMetadata(tableName);
    return columnTypeCache.get(tableName);
  }

  List<String> columnNames(String tableName) {
    loadColumnMetadata(tableName);
    return columnNameCache.get(tableName);
  }

  private void loadColumnMetadata(String tableName) {
    if (columnTypeCache.containsKey(tableName))
      return;

    String sql = "select * from " + tableName + " where 1 = 0";
    Map<String, SqlType> types = null;
    List<String> names = null;

    try (
        Statement statement = connection().createStatement();
        ResultSet resultSet = statement.executeQuery(sql)
    ) {
      types = new LinkedHashMap<>();
      names = new ArrayList<>();
      ResultSetMetaData meta = resultSet.getMetaData();
      for (int index = 1; index <= meta.getColumnCount(); index++) {
        String label = meta.getColumnLabel(index);
        names.add(label);
        types.put(label.toLowerCase(), SqlType.fromJdbcType(meta.getColumnType(index)));
      }
    } catch (SQLException ex) {
      Log.fatal(SQLException.class, "Could not read column metadata for [" + tableName + "]: " + ex.getMessage());
    }
    columnTypeCache.put(tableName, types);
    columnNameCache.put(tableName, names);
  }

  private void clearCaches() {
    columnTypeCache.clear();
    columnNameCache.clear();
  }
}
