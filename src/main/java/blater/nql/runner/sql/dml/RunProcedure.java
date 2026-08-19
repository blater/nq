package blater.nql.runner.sql.dml;

import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.domain.DmlExecutionResult;
import blater.nql.runner.sql.domain.SqlRow;
import blater.nql.runner.sql.domain.SqlStatement;
import blater.nql.runner.sql.dml.statementbuilder.ProcedureStatementBuilder;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.util.Log;

/*
 * Responsibility: Builds and executes one procedure call for one
 * mapped input row.
 */
public final class RunProcedure {
  public RunProcedure() {}

  public static DmlExecutionResult execute(NestStatement stmt, SqlRow row, SqlExecutor sqlExecutor) {
    SqlStatement sqlStmt = ProcedureStatementBuilder.build(stmt, row);

    Log.debug("exec PROCEDURE [{}]", sqlStmt.getSql());
    int rowcount = sqlExecutor.run(sqlStmt, stmt.getErrorHandling());
    if (rowcount < 0) {
      Log.error("Problem running a procedure statement [{}]", sqlStmt.getSql());
    }
    return DmlExecutionResult.EMPTY;
  }
}
