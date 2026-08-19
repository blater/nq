package blater.nql.runner.sql;

import blater.nql.runner.sql.domain.QueryColumn;
import blater.nql.runner.sql.domain.QueryResultRow;
import blater.nql.domain.SqlType;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.runner.sql.SqlRowCursor;
import blater.nql.testsupport.H2Database;
import blater.nql.util.Template;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlRowCursorNamedAccessTest {
  @Test
  void fetchesRowsAndExposesCurrentResultRow() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "insert into person (personid, firstname) values (1, 'Alice')",
          "insert into person (personid, firstname) values (2, 'Bob')");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      String sql = Template.expand(
          "select personid as \"personid\", firstname as \"firstname\" from person " +
              "where firstname like '${root.namePrefix}%' " +
              "order by personid asc",
          Map.of("root.namePrefix", "A"));

      try (SqlRowCursor cursor = sqlExecutor.query(sql)) {
        assertTrue(cursor.next());
        QueryResultRow row = cursor.row();
        assertEquals("Alice", row.getStringValue("firstname"));
        assertFalse(row.isNull("firstname"));
        assertFalse(cursor.next());
        assertNull(cursor.row());
      } finally {
        sqlExecutor.close();
      }
    }
  }

  @Test
  void exposesExactQuotedLabelsWithoutChangingTheirCase() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());

      try (SqlRowCursor cursor = sqlExecutor.query(
          "select 1 as \"col1\", 'select_branch_0' as \"hiql_select_branch\"")) {
        assertTrue(cursor.next());
        QueryResultRow row = cursor.row();
        assertEquals("1", row.getStringValue("col1"));
        assertEquals("select_branch_0", row.getStringValue("hiql_select_branch"));
        assertEquals("", row.getStringValue("COL1"), "labels are case-sensitive: COL1 != col1");
        assertFalse(cursor.next());
      } finally {
        sqlExecutor.close();
      }
    }
  }

  @Test
  void zeroRowSelectDoesNotRollback() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table audit_log (id integer primary key, message varchar(80))");
      database.execute("insert into audit_log (id, message) values (1, 'before warning')");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try (SqlRowCursor cursor = sqlExecutor.query("select message from audit_log where id = 99")) {
        sqlExecutor.setAutoCommit(false);

        assertFalse(cursor.next());
        assertNull(cursor.row());
      } finally {
        sqlExecutor.close();
      }
      assertEquals(1, database.queryInt("select count(*) from audit_log"));
    }
  }

  @Test
  void missingColumnsReadAsNullOrEmpty() {
    QueryResultRow row = new QueryResultRow(
        Map.of("present", QueryColumn.builder().columnName("present").sqlType(SqlType.STRING).columnValue("value").build()));
    assertNull(row.getValue("missing"));
    assertEquals("", row.getStringValue("missing"));
  }

  @Test
  void sqlNullStillFormatsAsEmptyString() {
    QueryResultRow row = new QueryResultRow(
      Map.of("nullable", QueryColumn.builder().columnName("nullable").sqlType(SqlType.STRING).columnValue(null).build())
    );
    assertEquals("", row.getStringValue("nullable"));
    assertTrue(row.isNull("nullable"));
  }

  @Test
  void decimalValuesUsePlainNotation() {
    QueryResultRow row = new QueryResultRow(Map.of(
        "whole", QueryColumn.builder()
            .columnName("whole")
            .sqlType(SqlType.NUMBER)
            .columnValue(new BigDecimal("2.5E+2"))
            .build(),
        "fraction", QueryColumn.builder()
            .columnName("fraction")
            .sqlType(SqlType.NUMBER)
            .columnValue(new BigDecimal("1E-7"))
            .build()));

    assertEquals("250", row.getStringValue("whole"));
    assertEquals("0.0000001", row.getStringValue("fraction"));
  }
}
