package blater.nq.runner.sql.dml.statementbuilder;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlStatement;

import java.util.ArrayList;
import java.util.List;

/*
 * Responsibility: Builds a parameterized INSERT statement from one
 * parsed statement and one mapped SQL row.
 */
public class InsertStatementBuilder {

  public static SqlStatement build(NestStatement stmt, SqlRow row) {
    StringBuilder colnames = new StringBuilder();
    StringBuilder colvalues = new StringBuilder();
    String dbAssignedIdentityColumn = null;
    List<Object> parameters = new ArrayList<>();

    for (SqlColumn col : row.getColumns()) {
      if (col.isUid()) {
        dbAssignedIdentityColumn = col.sqlName();
        continue;
      }

      if (!col.missingData()) {
        if (!colnames.isEmpty()) {
          colnames.append(", ");
          colvalues.append(", ");
        }
        colnames.append(col.sqlName());
        colvalues.append(col.sqlExpression());
        parameters.add(col.bindValue());
      }
    }

    String sql = "insert into " + stmt.getTargetName() + " (" + colnames + ") values (" + colvalues + ")";

    SyntaxErrorType status = colnames.isEmpty()
        ? SyntaxErrorType.EMPTY_INSERT
        : SyntaxErrorType.OK;

    return new SqlStatement(
        status,
        sql,
        parameters,
        dbAssignedIdentityColumn,
        false,
        row,
        stmt);
  }
}
