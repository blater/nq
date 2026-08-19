package blater.nql.runner.sql.dml.statementbuilder;

import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.domain.SqlColumn;
import blater.nql.runner.sql.domain.SqlRow;
import blater.nql.runner.sql.domain.SqlStatement;

import java.util.ArrayList;
import java.util.List;

import static blater.nql.runner.SyntaxErrorType.*;

/*
 * Responsibility: Builds a parameterized DELETE statement from one
 * parsed statement and one mapped SQL row.
 */
public final class DeleteStatementBuilder {
  private DeleteStatementBuilder() {}

  public static SqlStatement build(NestStatement stmt, SqlRow row) {
    List<Object> parameters = new ArrayList<>();
    StringBuilder where = new StringBuilder();

    for (SqlColumn column : row.getColumns()) {
      if (!column.isKey()) {
        continue;
      }
      if (column.isUid()) {
        return new SqlStatement(DELETE_UID_KEY, null);
      }
      if (column.missingData()) {
        return new SqlStatement(DELETE_MISSING_KEY, null);
      }

      where.append(parameters.isEmpty() ? "" : " and ")
          .append(column.sqlName()).append(" = ").append(column.sqlExpression());
      parameters.add(column.bindValue());
    }
    if (parameters.isEmpty()) {
      return new SqlStatement(DELETE_MISSING_KEY, null);
    }
    return new SqlStatement(OK, "delete from " + stmt.getTargetName() + " where " + where, parameters);
  }
}
