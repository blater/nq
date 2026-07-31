package blater.nq.runner.sql.domain;

import blater.nq.domain.SqlType;
import blater.nq.util.Template;

import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

/*
 * Responsibility: Holds one mapped SQL column value plus the SQL
 * expression and metadata needed to bind it.
 */
public record SqlColumn(
    ColumnDefinition definition,
    Object rawValue,
    String sqlExpression)
{
  public SqlColumn(ColumnDefinition definition, Object rawValue) {
    this(definition, rawValue, "?");
  }

  public static SqlColumn from( ColumnDefinition definition, Object rawValue, Map<String, String> parameters)
  {
    String sqlExpression = hasValue(definition.sqlFunction())
        ? Template.expand(definition.sqlFunction().replace("${0}", "?"), parameters)
        : "?";

    return new SqlColumn(definition, rawValue, sqlExpression);
  }

  public boolean missingData() {
    return (rawValue == null) ? "?".equals(sqlExpression): blankNonString();
  }

  public Object bindValue() {
    if (rawValue == null || blankNonString()) {
      return null;
    }
    return definition.sqlType().cast(rawValue);
  }

  public SqlColumn withSqlType(SqlType sqlType) {
    return new SqlColumn(
        new ColumnDefinition(
            definition.sqlName(),
            sqlType,
            definition.sqlFunction(),
            definition.key(),
            definition.keyNumber(),
            definition.columnDataSourceType()),
        rawValue,
        sqlExpression);
  }

  public SqlColumn withSqlName(String sqlName) {
    return new SqlColumn(
        new ColumnDefinition(
            sqlName,
            definition.sqlType(),
            definition.sqlFunction(),
            definition.key(),
            definition.keyNumber(),
            definition.columnDataSourceType()),
        rawValue,
        sqlExpression);
  }

  public String sqlName() {
    return definition.sqlName();
  }

  public boolean isKey() {
    return definition.key();
  }

  public boolean isUid() {
    return definition.isUid();
  }

  public boolean isDbAssigned() {
    return definition.isDbAssigned();
  }

  private boolean blankNonString() {
    return rawValue instanceof String text
        && text.isEmpty()
        && definition.sqlType() != SqlType.STRING;
  }
}
