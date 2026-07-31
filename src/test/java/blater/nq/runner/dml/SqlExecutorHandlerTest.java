package blater.nq.runner.dml;

import blater.nq.parser.script.ErrorBehaviourType;
import blater.nq.parser.script.ErrorStrategy;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlExecutorHandlerTest {
  @Test
  void zeroAffectedRowsAreNotExecutionErrors() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table audit_log (" +
              "id integer primary key, " +
              "message varchar(80))");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      int result;
      try {
        result = sqlExecutor.runLiteral(
            "update audit_log set message = 'missing' where id = 99",
            new ErrorStrategy(ErrorBehaviourType.FAIL, ErrorBehaviourType.FAIL));
      } finally {
        sqlExecutor.close();
      }

      assertEquals(0, result);
    }
  }

  @Test
  void rollsBackAndFailsWhenExecutionErrorRequestsRollback() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table audit_log (" +
              "id integer primary key, " +
              "message varchar(80))");
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      sqlExecutor.setAutoCommit(false);
      sqlExecutor.runLiteral(
          "insert into audit_log (id, message) values (1, 'before error')",
          new ErrorStrategy(ErrorBehaviourType.FAIL, ErrorBehaviourType.FAIL));

      ErrorStrategy handling = new ErrorStrategy(
          ErrorBehaviourType.FAIL,
          ErrorBehaviourType.FAIL);
      handling.setOnError(ErrorBehaviourType.FAIL, true);

      try {
        assertThrows(IllegalStateException.class,
            () -> sqlExecutor.runLiteral(
                "insert into missing_table (id) values (1)",
                handling));
      } finally {
        sqlExecutor.close();
      }
      assertEquals(0, database.queryInt("select count(*) from audit_log"));
    }
  }

  @Test
  void stripsCommentsFromDmlBeforeExecuting() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table audit_log (" +
              "id integer primary key, " +
              "message varchar(80))");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      int result;
      try {
        result = sqlExecutor.runLiteral(
            "insert into audit_log (id, message) /* who */ values (1, 'hi') -- trailing note\n",
            new ErrorStrategy(ErrorBehaviourType.FAIL, ErrorBehaviourType.FAIL));
      } finally {
        sqlExecutor.close();
      }

      assertEquals(1, result);
      assertEquals(1, database.queryInt("select count(*) from audit_log where message = 'hi'"));
    }
  }

  @Test
  void executionErrorCanContinueInBestEffortMode() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      int result;
      try {
        result = sqlExecutor.runLiteral(
            "insert into missing_table (id) values (1)",
            new ErrorStrategy(
                ErrorBehaviourType.FAIL,
                ErrorBehaviourType.BEST_EFFORT));
      } finally {
        sqlExecutor.close();
      }

      assertEquals(0, result);
    }
  }
}
