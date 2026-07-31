package blater.nq.runner.sql.dml.statementbuilder;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlStatement;

import java.util.ArrayList;
import java.util.List;

/*
 * Responsibility: Builds a parameterized procedure call from one
 * parsed statement and one mapped SQL row.
 */
public class ProcedureStatementBuilder {
  public static SqlStatement build(NestStatement stmt, SqlRow row) {
    List<Object> parameters = new ArrayList<>(row.getColumns().size());

    StringBuilder sql = new StringBuilder("execute procedure " + stmt.getTargetName() + "(");

    for (SqlColumn col : row.getColumns()) {
      if (col.missingData()) {
        continue;
      }
      sql.append((parameters.isEmpty() ? "" : ", ") + col.sqlName() + " = " + col.sqlExpression());
      parameters.add(col.bindValue());
    }
    sql.append(")");

    return new SqlStatement(SyntaxErrorType.OK, sql.toString(), parameters);
  }
}
