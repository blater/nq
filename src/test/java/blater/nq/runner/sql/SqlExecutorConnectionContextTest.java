package blater.nq.runner.sql;

import blater.nq.parser.script.ErrorBehaviourType;
import blater.nq.parser.script.ErrorStrategy;
import blater.nq.domain.SqlType;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Responsibility: Verifies that SqlExecutor owns the active JDBC
 * connection and resets connection-local metadata caches.
 */
class SqlExecutorConnectionContextTest {
  @Test
  void constructorOpensConfiguredDatabase() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try {
        sqlExecutor.runLiteral(
            "create table audit_log (id integer primary key, message varchar(80))",
            handling());
        sqlExecutor.runLiteral(
            "insert into audit_log (id, message) values (1, 'created')",
            handling());
      } finally {
        sqlExecutor.close();
      }

      assertEquals("created", database.queryString("select message from audit_log where id = 1"));
    }
  }

  @Test
  void constructorOpensDatabaseBySupportedDriverName() throws Exception {
    try (H2Database database = new H2Database()) {
      Map<String, String> params = new LinkedHashMap<>(database.jdbcProperties());
      params.remove("jdbc.class.name");
      params.put("jdbc.driver", "h2");

      SqlExecutor sqlExecutor = new SqlExecutor(params);
      try {
        sqlExecutor.runLiteral(
            "create table audit_log (id integer primary key, message varchar(80))",
            handling());
        sqlExecutor.runLiteral(
            "insert into audit_log (id, message) values (1, 'created')",
            handling());
      } finally {
        sqlExecutor.close();
      }

      assertEquals("created", database.queryString("select message from audit_log where id = 1"));
    }
  }

  @Test
  void closeClearsActiveConnection() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      sqlExecutor.close();

      IllegalStateException thrown = assertThrows(
          IllegalStateException.class,
          () -> sqlExecutor.runLiteral("select 1", handling()));

      assertEquals("Database connection has not been opened.", thrown.getMessage());
    }
  }

  @Test
  void reconnectClearsTableMetadataCache() throws Exception {
    try (H2Database first = new H2Database();
         H2Database second = new H2Database()) {
      first.execute("create table person (personid integer)");
      second.execute("create table person (personid varchar(20))");

      SqlExecutor sqlExecutor = new SqlExecutor(first.jdbcProperties());
      assertEquals(SqlType.INTEGER, sqlExecutor.columnTypes("person").get("personid"));

      sqlExecutor.connect(second.jdbcProperties());
      try {
        assertEquals(SqlType.STRING, sqlExecutor.columnTypes("person").get("personid"));
      } finally {
        sqlExecutor.close();
      }
    }
  }

  @Test
  void missingRelationErrorsSuggestCatalogDiscovery() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try {
        SQLException thrown = assertThrows(
            SQLException.class,
            () -> sqlExecutor.query("select id from missing_relation"));

        assertTrue(thrown.getMessage().contains("nq catalog '*'"));
        assertTrue(thrown.getMessage().contains("same input or connection options"));
      } finally {
        sqlExecutor.close();
      }
    }
  }

  private ErrorStrategy handling() {
    return new ErrorStrategy(ErrorBehaviourType.FAIL, ErrorBehaviourType.FAIL);
  }
}
