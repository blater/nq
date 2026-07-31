package blater.nq.runner.sql;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/*
 * Responsibility: Reads JDBC database metadata into the standard output hierarchy.
 */
public final class Catalog {
  private static final Set<String> USER_TABLE_TYPES = Set.of("TABLE", "BASE TABLE");
  private static final Set<String> SYSTEM_NAMES = Set.of(
      "CTXSYS", "DBSNMP", "INFORMATION_SCHEMA", "MDSYS", "MYSQL", "NQ_INTERNAL", "OUTLN", "PERFORMANCE_SCHEMA", "PG_CATALOG",
      "PG_TOAST", "SYS", "SYSCAT", "SYSFUN", "SYSIBM", "SYSIBMADM", "SYSPROC", "SYSSTAT", "SYSTEM",
      "SYSTEM_LOBS", "XDB");

  private Catalog() { }

  public static Hierarchy read(Connection connection, String tablePattern) {
    Node root = new Node("catalog");
    try {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet tables = metadata.getTables(null, null, "%", USER_TABLE_TYPES.toArray(String[]::new))) {
        while (tables.next()) {
          addUserTable(root, metadata, tables, tablePattern);
        }
      }
    } catch (SQLException ex) {
      return Log.fatal(IllegalStateException.class, "Could not read database catalog.", ex);
    }
    return new Hierarchy(root);
  }

  private static void addUserTable(
      Node root,
      DatabaseMetaData metadata,
      ResultSet tables,
      String tablePattern) throws SQLException {

    String catalog = tables.getString("TABLE_CAT");
    String schema = tables.getString("TABLE_SCHEM");
    String tableName = tables.getString("TABLE_NAME");
    if (!isUserSchema(catalog, schema) || !matches(tableName, tablePattern)) {
      return;
    }

    if (tablePattern == null) {
      root.addNode(tableSummaryNode(tableName));
    } else {
      root.addNode(tableDetailNode(
          metadata,
          catalog,
          schema,
          tableName,
          tables.getString("TABLE_TYPE")));
    }
  }

  private static Node tableSummaryNode(String tableName) {
    Node tableNode = new Node("table");
    addValue(tableNode, "name", tableName);
    return tableNode;
  }

  private static Node tableDetailNode(
      DatabaseMetaData metadata,
      String catalog,
      String schema,
      String tableName,
      String tableType) throws SQLException {

    Node tableNode = new Node("table");
    addValue(tableNode, "name", tableName);
    //addOptionalValue(tableNode, "catalog", catalog);
    //addOptionalValue(tableNode, "schema", schema);
    //addValue(tableNode, "type", tableType);

    Node columnsNode = new Node("columns");
    tableNode.addNode(columnsNode);
    String searchEscape = metadata.getSearchStringEscape();
    try (ResultSet columns = metadata.getColumns(
        catalog,
        exactPattern(schema, searchEscape),
        exactPattern(tableName, searchEscape),
        "%")) {
      while (columns.next()) {
        columnsNode.addNode(columnNode(columns));
      }
    }
    return tableNode;
  }

  private static Node columnNode(ResultSet columns) throws SQLException {
    Node columnNode = new Node("column");
    addValue(columnNode, "name", columns.getString("COLUMN_NAME"));
    addValue(columnNode, "type", columns.getString("TYPE_NAME"));
    addValue(columnNode, "nullable", Boolean.toString(columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable));
    //addValue(columnNode, "position", columns.getString("ORDINAL_POSITION"));
    return columnNode;
  }

  private static boolean matches(String tableName, String tablePattern) {
    if (tablePattern == null) {
      return true;
    }
    String regex = "^" + Pattern.quote(tablePattern).replace("*", "\\E.*\\Q") + "$";
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
        .matcher(tableName)
        .matches();
  }

  public static boolean isUserSchema(String catalog, String schema) {
    return isNotSystemName(catalog) && isNotSystemName(schema);
  }

  private static boolean isNotSystemName(String name) {
    if (name == null || name.isBlank()) {
      return true;
    }
    String ucName = name.trim().toUpperCase();
    return !SYSTEM_NAMES.contains(ucName) && !ucName.startsWith("SYS_");
  }

  private static void addValue(Node parent, String name, String value) {
    Node child = new Node(name);
    child.setValue(value == null ? "" : value);
    parent.addNode(child);
  }


  private static String exactPattern(String value, String searchEscape) {
    if (value == null || searchEscape == null || searchEscape.isEmpty()) {
      return value;
    }
    return value
        .replace(searchEscape, searchEscape + searchEscape)
        .replace("%", searchEscape + "%")
        .replace("_", searchEscape + "_");
  }

}
