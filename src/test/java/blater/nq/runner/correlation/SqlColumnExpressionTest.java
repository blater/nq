package blater.nq.runner.correlation;

import blater.nq.runner.sql.domain.ColumnDataSourceType;
import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.domain.SqlType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SqlColumnExpressionTest {
  @Test
  public void leavesStringValuesUnquotedForPreparedBinding() {
    var column = column(column("nickname", SqlType.STRING, ""), "O'Reilly");

    assertEquals("O'Reilly", column.rawValue());
    assertEquals("?", column.sqlExpression());
  }

  @Test
  public void keepsNullValuesAsMissingData() {
    var column = column(column("nickname", SqlType.STRING, ""), null);

    assertNull(column.rawValue());
    assertEquals("?", column.sqlExpression());
  }

  @Test
  public void convertsSqlFunctionSelfReferenceToPlaceholder() {
    var column = column(column("personid", SqlType.INTEGER, "coalesce(${0}, 0)"), "7");

    assertEquals("7", column.rawValue());
    assertEquals("coalesce(?, 0)", column.sqlExpression());
  }

  @Test
  public void expandsTemplateParametersInsideSqlFunctions() {
    Map<String, String> parameters = new HashMap<>();
    parameters.put("fallback", "0");
    var column = SqlColumn.from(
        column("personid", SqlType.INTEGER, "coalesce(${0}, ${fallback})"),
        "7",
        parameters);

    assertEquals("coalesce(?, 0)", column.sqlExpression());
  }

  private SqlColumn column(ColumnDefinition definition, Object rawValue) {
    return SqlColumn.from(definition, rawValue, Map.of());
  }

  private ColumnDefinition column(String sqlName, SqlType sqlType, String sqlFunction) {
    return new ColumnDefinition(sqlName, sqlType, sqlFunction, false, 0, ColumnDataSourceType.NORMAL);
  }
}
