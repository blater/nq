package blater.nq.runner.sql.dml;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.domain.DmlExecutionResult;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.runner.sql.domain.SqlStatement;
import blater.nq.runner.sql.dml.statementbuilder.UpdateStatementBuilder;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.util.Log;

import java.util.Map;

/*
 * Responsibility: Builds and executes one UPDATE statement for one
 * mapped input row.
 */
public final class RunUpdate {
  private RunUpdate() {}

  public static DmlExecutionResult execute(NestStatement stmt, SqlRow row, SqlExecutor sqlExecutor) {
    row = row.withColumnTypes(sqlExecutor.columnTypes(stmt.getTargetName()));
    SqlStatement sqlStmt = UpdateStatementBuilder.build(stmt, row);

    if (sqlStmt.isUidInvolved()) {
      return RunInsert.execute(stmt, row, sqlExecutor);
    }
    if (!sqlExecutor.checkStatementError(sqlStmt.getStatus(), stmt.getErrorHandling())) {
      return DmlExecutionResult.EMPTY;
    }

    Log.debug("UPDATE [{}]", sqlStmt.getSql());
    int rowcount = sqlExecutor.run(sqlStmt, stmt.getErrorHandling());
    Log.debug("Rows affected = {}", rowcount);

    if (rowcount < 0) {
      Log.error("Problem running the SQL statement [{}]", sqlStmt.getSql());
      return DmlExecutionResult.EMPTY;

    } else if (rowcount == 0 && sqlStmt.isUidInvolved()) {
      Log.info("No rows updated; running insert fallback.");
      return RunInsert.execute(stmt, row, sqlExecutor);

    } else {
      Map<String, String> dbAssignedValues = stmt.getReturnMappings().isEmpty()
          ? RunSelectDbAssignedValues.runSelect(sqlStmt, sqlExecutor)
          : RunSelectDmlReturnValues.selectForUpdate(stmt, row, sqlExecutor);
      return DmlExecutionResult.of(dbAssignedValues);
    }
  }
}
