package blater.nql.runner.sql;

import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.runner.sql.domain.SqlColumn;
import blater.nql.runner.sql.domain.SqlRow;
import blater.nql.util.Log;
import blater.nql.util.Template;

import java.sql.SQLException;
import java.util.*;

/*
 * Responsibility: Drains a SqlRowCursor into row maps keyed by
 * lowercased column label for temp-rowset capture.
 *
 * Ordinary SELECT output streams through the same cursor without
 * materialising all rows.
 */
public final class Capture {
  private Capture() {}

  public static Map<String, List<Map<String, Object>>> captureTempRowset(NestStatement stmt, Map<String, String> parameters, SqlExecutor sqlExecutor) {
    String sql = Template.expand(stmt.getSql(), parameters);
    Map<String, List<Map<String, Object>>> tempRowsets = new HashMap<>();
    try (SqlRowCursor cursor = sqlExecutor.query(sql)) {
      tempRowsets.put(stmt.getTargetName(), readAll(cursor));
    } catch (Exception ex) {
      Log.error("Exception retrieving temp rowset [{}]: {}", stmt.getTargetName(), ex.getMessage());
    }
    return tempRowsets;
  }

  static List<Map<String, Object>> readAll(SqlRowCursor cursor) throws SQLException {
    int columnCount = cursor.columnCount();
    String[] labels = new String[columnCount + 1];
    for (int i = 1; i <= columnCount; i++) {
      labels[i] = cursor.columnLabel(i).toLowerCase();
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    while (cursor.next()) {
      Map<String, Object> row = new LinkedHashMap<>(columnCount);
      for (int i = 1; i <= columnCount; i++) {
        row.put(labels[i], cursor.value(i));
      }
      rows.add(row);
    }
    return rows;
  }


  public static SqlRow toSqlRow(List<InputToColumnMap> mappings, Map<String, Object> tempRow, Map<String, String> parameters) {
    List<SqlColumn> fields = new ArrayList<>();
    for (InputToColumnMap m : mappings) {
      String key = m.xpathMapping().toLowerCase();
      Object raw = tempRow.get(key);
      fields.add(SqlColumn.from(m.columnDefinition(), raw, parameters));
    }
    return new SqlRow(fields);
  }
}
