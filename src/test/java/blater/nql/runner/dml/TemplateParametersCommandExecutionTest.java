package blater.nql.runner.dml;

import blater.nql.parser.ScriptParser;
import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.dml.RunLiteralSql;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateParametersCommandExecutionTest {
  @Test
  void literalSqlUsesInputDataWithoutJdomParameters() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute("create table audit_log (dishid integer, dishname varchar(80), actor varchar(80))");
      var statement =
          parseStatement("insert into audit_log (dishid, dishname, actor) "
              + "values (${message.dish.[id]}, '${message.dish.dishname}', '${actor}');\n");
      Map<String, String> params = Map.of(
          "message.dish.[id]", "12",
          "message.dish.dishname", "Dry Toast",
          "actor", "system");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      boolean executed;
      try {
        executed = RunLiteralSql.execute(statement, params, sqlExecutor);
      } finally {
        sqlExecutor.close();
      }

      assertTrue(executed);
      assertEquals("Dry Toast", database.queryString("select dishname from audit_log where dishid = 12"));
      assertEquals("system", database.queryString("select actor from audit_log where dishid = 12"));
    }
  }

  private NestStatement parseStatement(String script) throws Exception {
    return ScriptParser.parse(script).statements().getFirst();
  }
}
