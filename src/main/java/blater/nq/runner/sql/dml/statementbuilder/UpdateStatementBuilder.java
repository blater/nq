package blater.nq.runner.sql.dml.statementbuilder;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlStatement;

import java.util.ArrayList;
import java.util.List;

/*
 * Responsibility: Builds a parameterized UPDATE statement from one
 * parsed statement and one mapped SQL row.
 */
public class UpdateStatementBuilder {
  public static SqlStatement build(NestStatement stmt, SqlRow row) {
    StringBuilder sql = new StringBuilder("update ").append(stmt.getTargetName()).append(" set ");
    StringBuilder where = new StringBuilder();
    boolean firstColumn = true;
    boolean firstKey = true;
    boolean missingKey = false;
    boolean hasKey = false;
    boolean uidInvolved = false;
    List<Object> setParameters = new ArrayList<>();
    List<Object> keyParameters = new ArrayList<>();

    for (SqlColumn col : row.getColumns()) {
      if (col.isKey()) {
        hasKey = true;

        if (col.isUid()) {
          uidInvolved = true;
          break;
        }

        if (col.missingData()) {
          missingKey = true;
          break;
        }

        if (!firstKey) {
          where.append(" and ");
        }
        firstKey = false;
        where.append(col.sqlName()).append(" = ").append(col.sqlExpression());
        keyParameters.add(col.bindValue());
        continue;
      }

      if (!col.missingData()) {
        if (!firstColumn) {
          sql.append(", ");
        }
        firstColumn = false;
        sql.append(col.sqlName()).append(" = ").append(col.sqlExpression());
        setParameters.add(col.bindValue());
      }
    }

    SyntaxErrorType status = SyntaxErrorType.OK;
    if (!uidInvolved) {
      if (!hasKey || missingKey) {
        status = SyntaxErrorType.UPDATE_MISSING_KEY;
      } else if (firstColumn) {
        status = SyntaxErrorType.UPDATE_NO_VALUES;
      }
    }
    if (status == SyntaxErrorType.OK && !uidInvolved) {
      sql.append(" where ").append(where);
    }
    List<Object> parameters = new ArrayList<>(setParameters);
    parameters.addAll(keyParameters);

    return new SqlStatement(
        status,
        sql.toString(),
        parameters,
        null,
        uidInvolved,
        row,
        stmt
    );
  }
}
