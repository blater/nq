package blater.nq.runner.sql.dml;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.util.Log;
import blater.nq.util.Template;

import java.util.Map;

/*
 * Responsibility: Expands and executes one literal SQL statement.
 */
public class RunLiteralSql {
  private RunLiteralSql() {}

  public static boolean execute(
      NestStatement stmt, Map<String, String> parameters, SqlExecutor sqlExecutor) {
    if (stmt.getSql() == null) {
      Log.error("The isLiteral SQL flag is set but no SQL has been specified: {}", stmt);
      return false;
    }

    String literalSQL = Template.expand(stmt.getSql(), parameters);
    if (literalSQL == null || literalSQL.isEmpty()) {
      Log.warn("No SQL specified in a isLiteral SQL XML element.");
      return false;
    }
    sqlExecutor.runLiteral(literalSQL, stmt.getErrorHandling());

    return true;
  }
}
