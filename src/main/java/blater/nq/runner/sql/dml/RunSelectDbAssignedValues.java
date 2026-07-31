package blater.nq.runner.sql.dml;

import blater.nq.runner.sql.domain.SqlStatement;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.SqlRowCursor;
import blater.nq.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Reads database-assigned values using a prepared
 * return-value SELECT.
 */
public class RunSelectDbAssignedValues {

  public static Map<String, String> runSelect(String refreshSql,
                                              List<Object> parameters,
                                              List<String> dbAssignedColumnNames,
                                              SqlExecutor sqlExecutor)
  {
    Log.debug("Selecting database-assigned data: {}", refreshSql);
    if (refreshSql == null || refreshSql.isEmpty() || dbAssignedColumnNames == null || dbAssignedColumnNames.isEmpty()) {
      return Map.of();
    }
    Map<String, String> refreshedValues = new LinkedHashMap<>();

    try (SqlRowCursor cursor = sqlExecutor.query(refreshSql, parameters)) {
      if (!cursor.next()) {
        Log.warn("The database-assigned select [{}] returned no rows.", refreshSql);
        return Map.of();
      }

      for (int idx = 0; idx < dbAssignedColumnNames.size(); idx++) {
        refreshedValues.put(
            dbAssignedColumnNames.get(idx),
            cursor.stringValue(idx + 1));
      }
    } catch (Exception sqlex) {
      Log.warn("Exception refreshing database-assigned data from the database: {}", sqlex.getMessage());
      return Map.of();
    }
    return refreshedValues;
  }

  public static Map<String, String> runSelect(SqlStatement stmt, SqlExecutor sqlExecutor) {
    return runSelect(
        stmt.getDbAssignedValuesSql(null),
        stmt.getDbAssignedValuesParameters(null),
        stmt.getDbAssignedColumns(),
        sqlExecutor);
  }
}
