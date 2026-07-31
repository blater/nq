package blater.nq.runner.sql;

import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.SqlRowCursor;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlRowCursorTest {
  @Test
  void closeReleasesBothResultSetAndStatement() throws Exception {
    try (H2Database database = new H2Database()) {
      Statement statement = database.connection().createStatement();
      ResultSet resultSet = statement.executeQuery("select 1");
      SqlRowCursor cursor = new SqlRowCursor(statement, resultSet);

      assertTrue(cursor.next());
      cursor.close();

      assertTrue(resultSet.isClosed(), "result set should be closed");
      assertTrue(statement.isClosed(), "statement should be closed");
    }
  }

  @Test
  void streamsRowsThroughIndexedApi() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table n (v integer)",
          "insert into n (v) values (1)",
          "insert into n (v) values (2)");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try (SqlRowCursor cursor = sqlExecutor.query("select v from n order by v")) {
        assertEquals(1, cursor.columnCount());
        assertTrue(cursor.next());
        assertEquals("1", cursor.stringValue(1));
        assertTrue(cursor.next());
        assertEquals(2, ((Number) cursor.value(1)).intValue());
        assertFalse(cursor.next());
      } finally {
        sqlExecutor.close();
      }
    }
  }
}
