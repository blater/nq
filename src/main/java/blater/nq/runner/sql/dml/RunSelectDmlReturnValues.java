package blater.nq.runner.sql.dml;

import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.ReturnMapping;
import blater.nq.runner.sql.domain.ColumnDataSourceType;
import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.domain.SqlType;
import blater.nq.runner.sql.SqlExecutor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Selects database values requested by explicit DML returns clauses.
 */
final class RunSelectDmlReturnValues {
  private RunSelectDmlReturnValues() {}

  static Map<String, String> selectForInsert(NestStatement stmt, SqlRow row, String generatedKey, SqlExecutor sqlExecutor)
  {
    if (stmt.getReturnMappings().isEmpty()) {
      return Map.of();
    }

    List<SqlColumn> keyColumns = insertReturnKeyColumns(stmt, row, generatedKey);
    Map<String, String> generatedValues = new LinkedHashMap<>();
    if (generatedKey != null) {
      generatedValues.put(stmt.getReturnMappings().getFirst().getColumnName(), generatedKey);
    }

    Map<String, String> selectedValues = selectDbAssignedValues(stmt, keyColumns, sqlExecutor);
    if (selectedValues.isEmpty()) {
      return generatedValues;
    }
    generatedValues.putAll(selectedValues);
    return generatedValues;
  }

  static Map<String, String> selectForUpdate(NestStatement stmt, SqlRow row, SqlExecutor sqlExecutor) {
    if (stmt.getReturnMappings().isEmpty()) {
      return Map.of();
    }
    return selectDbAssignedValues(stmt, row.getColumns().stream()
        .filter(column -> column.isKey() && !column.missingData())
        .toList(), sqlExecutor);
  }

  private static List<SqlColumn> insertReturnKeyColumns(NestStatement stmt, SqlRow row, String generatedKey)
  {
    if (generatedKey != null && !stmt.getReturnMappings().isEmpty()) {
      ReturnMapping firstReturn = stmt.getReturnMappings().getFirst();
      return List.of(new SqlColumn(
          new ColumnDefinition(
              firstReturn.getColumnName(),
              SqlType.STRING,
              "",
              true,
              1,
              ColumnDataSourceType.NORMAL),
          generatedKey));
    }
    return row.getColumns().stream()
        .filter(column -> column.isKey() && !column.missingData())
        .toList();
  }

  private static Map<String, String> selectDbAssignedValues(NestStatement stmt, List<SqlColumn> keyColumns, SqlExecutor sqlExecutor)
  {
    if (keyColumns.isEmpty()) {
      return Map.of();
    }
    List<String> returnColumns = stmt.getReturnMappings().stream()
        .map(ReturnMapping::getColumnName)
        .toList();
    return RunSelectDbAssignedValues.runSelect(
        selectSql(stmt, returnColumns, keyColumns),
        keyColumns.stream().map(SqlColumn::bindValue).toList(),
        returnColumns,
        sqlExecutor);
  }

  private static String selectSql(NestStatement stmt, List<String> returnColumns, List<SqlColumn> keyColumns)
  {
    StringBuilder sql = new StringBuilder("select ");
    sql.append(String.join(", ", returnColumns));
    sql.append(" from ").append(stmt.getTargetName()).append(" where ");
    List<String> predicates = new ArrayList<>();
    for (SqlColumn column : keyColumns) {
      predicates.add(column.sqlName() + " = ?");
    }
    sql.append(String.join(" and ", predicates));
    return sql.toString();
  }
}
