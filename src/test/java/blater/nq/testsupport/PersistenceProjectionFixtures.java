package blater.nq.testsupport;

import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.NestSqlStatementType;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.ColumnDataSourceType;
import blater.nq.domain.SqlType;

import java.util.List;
import java.util.stream.IntStream;

public final class PersistenceProjectionFixtures {
  private PersistenceProjectionFixtures() {}

  public static NestStatement statement(NestSqlStatementType type, String targetName) {
    return NestStatement.dml(type, targetName, null, List.of(), List.of());
  }

  public static NestStatement procedureStatement(String targetName) {
    return NestStatement.proc(targetName, List.of());
  }

  public static ColumnDefinition column(
      String sqlName, String sqlType, boolean key, boolean uid, boolean dbAssigned) {
    return column(sqlName, sqlTypeFromText(sqlType), key, uid, dbAssigned);
  }

  public static ColumnDefinition column(
      String sqlName, SqlType sqlType, boolean key, boolean uid, boolean dbAssigned) {
    return new ColumnDefinition(sqlName, sqlType, "", key, key ? 1 : -99, role(uid, dbAssigned));
  }

  private static SqlType sqlTypeFromText(String text) {
    return switch (text.toLowerCase()) {
      case "string"  -> SqlType.STRING;
      case "integer" -> SqlType.INTEGER;
      case "long"    -> SqlType.LONG;
      case "short"   -> SqlType.SHORT;
      case "float"   -> SqlType.FLOAT;
      case "double"  -> SqlType.DOUBLE;
      case "date"    -> SqlType.DATE;
      default -> throw new IllegalArgumentException("Unknown SQL type in fixture: " + text);
    };
  }

  private static ColumnDataSourceType role(boolean uid, boolean dbAssigned) {
    if (uid) {
      return ColumnDataSourceType.GENERATED_UID;
    }
    if (dbAssigned) {
      return ColumnDataSourceType.DB_ASSIGNED;
    }
    return ColumnDataSourceType.NORMAL;
  }

  public static SqlRow row(List<ColumnDefinition> columns, String... values) {
    List<SqlColumn> fields = IntStream.range(0, columns.size())
        .mapToObj(idx -> new SqlColumn(columns.get(idx), values[idx]))
        .toList();
    return new SqlRow(fields);
  }

}
