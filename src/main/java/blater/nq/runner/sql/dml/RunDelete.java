package blater.nq.runner.sql.dml;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlStatement;
import blater.nq.runner.sql.dml.statementbuilder.DeleteStatementBuilder;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.util.Log;

/*
 * Responsibility: Builds and executes one DELETE statement for one
 * mapped input row.
 */
public final class RunDelete {
  public RunDelete() {}

  public static void execute(NestStatement stmt, SqlRow row, SqlExecutor sqlExecutor) {
    row = row.withColumnTypes(sqlExecutor.columnTypes(stmt.getTargetName()));
    SqlStatement deleteStmt = DeleteStatementBuilder.build(stmt, row);

    if (!sqlExecutor.checkStatementError(deleteStmt.getStatus(), stmt.getErrorHandling())) {
      return;
    }

    Log.debug("DELETE [{}]", deleteStmt.getSql());
    int rowcount = sqlExecutor.run(deleteStmt, stmt.getErrorHandling());
    Log.debug("Rows affected = {}", rowcount);
    if (rowcount < 0) {
      Log.error("Problem running a delete statement [{}]", deleteStmt.getSql());
    }
  }
}
