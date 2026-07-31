package blater.nq.runner.sql.domain;

import blater.nq.runner.sql.dml.mapping.DbAssignedNode;
import blater.nq.domain.SqlType;
import blater.nq.util.Log;
import lombok.Getter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

// Responsibility: Carries one mapped DML row's SQL columns and row-local write-back targets.
@Getter
public class SqlRow {
  private final List<SqlColumn> columns;
  private final List<DbAssignedNode> writeBackNodes;

  public SqlRow(List<SqlColumn> columns) {
    this(columns, List.of());
  }

  public SqlRow(List<SqlColumn> columns, List<DbAssignedNode> writeBackNodes) {
    this.columns = List.copyOf(columns);
    this.writeBackNodes = List.copyOf(writeBackNodes);
  }

  public Optional<SqlColumn> find(String sqlName) {
    return columns.stream().filter(f -> f.sqlName().equals(sqlName)).findFirst();
  }

  public SqlRow withColumnTypes(Map<String, SqlType> columnTypes) {
    return new SqlRow(columns.stream()
        .map(column -> column.withSqlType(columnTypes.getOrDefault(
            column.sqlName().toLowerCase(Locale.ROOT),
            column.definition().sqlType())))
        .toList(), writeBackNodes);
  }

  public SqlRow withPositionColumnNames(List<String> columnNames) {
    if (hasPositionColumns() && columns.size() != columnNames.size()) {
      Log.fatal(IllegalStateException.class,
          "Mapped value count does not match target table column count for positional insert.");
    }
    return new SqlRow(columns.stream()
        .map(column -> column.withSqlName(resolvePositionColumnName(column.sqlName(), columnNames)))
        .toList(), writeBackNodes);
  }

  public boolean hasPositionColumns() {
    return columns.stream().anyMatch(column -> column.sqlName().startsWith("$"));
  }

  private static String resolvePositionColumnName(String sqlName, List<String> columnNames) {
    if (!sqlName.startsWith("$")) {
      return sqlName;
    }
    int position;
    try {
      position = Integer.parseInt(sqlName.substring(1));
    } catch (NumberFormatException ex) {
      return Log.fatal(IllegalStateException.class, "Invalid positional insert column: " + sqlName, ex);
    }
    if (position < 1 || position > columnNames.size()) {
      return Log.fatal(IllegalStateException.class,
          "Mapped value count does not match target table column count for positional insert.");
    }
    return columnNames.get(position - 1);
  }
}
