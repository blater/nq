package blater.nq.e2e;

import blater.nq.parser.script.NestScript;
import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.runner.sql.domain.ColumnDataSourceType;
import blater.nq.runner.ScriptRunner;
import blater.nq.domain.SqlType;
import blater.nq.parser.script.NestStatement;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blater.nq.parser.script.NestSqlStatementType.INSERT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TempRowsetFlowTest {

  @Test
  void capturesQueryRowsAndInsertsViaTemp() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "insert into person (personid, firstname) values (1, 'Alice')",
          "insert into person (personid, firstname) values (2, 'Bob')",
          "create table audit (personid integer, name varchar(80))");

      var captureStmt = NestStatement.capture(
          "people_snap",
          "select personid, firstname from person order by personid");

      var insertStmt = NestStatement.dml(
          INSERT,
          "audit",
          "people_snap",
          List.of(
              new InputToColumnMap(
                  new ColumnDefinition("personid", SqlType.INTEGER, "", true, 1, ColumnDataSourceType.NORMAL),
                  "personid", null, false),
              new InputToColumnMap(
                  new ColumnDefinition("name", SqlType.STRING, "", false, -99, ColumnDataSourceType.NORMAL),
                  "firstname", null, false)),
          List.of());

      var script = new NestScript(List.of(captureStmt, insertStmt));
      assertNotNull(ScriptRunner.run(script, database.jdbcProperties()));

      assertEquals(2, database.queryInt("select count(*) from audit"));
      assertEquals("Alice", database.queryString("select name from audit where personid = 1"));
      assertEquals("Bob", database.queryString("select name from audit where personid = 2"));
    }
  }

  @Test
  void emptyQueryProducesNoInserts() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "create table audit (personid integer, name varchar(80))");

      var captureStmt = NestStatement.capture(
          "nobody",
          "select personid, firstname from person");

      var insertStmt = NestStatement.dml(
          INSERT,
          "audit",
          "nobody",
          List.of(
              new InputToColumnMap(
                  new ColumnDefinition("personid", SqlType.INTEGER, "", true, 1, ColumnDataSourceType.NORMAL),
                  "personid", null, false),
              new InputToColumnMap(
                  new ColumnDefinition("name", SqlType.STRING, "", false, -99, ColumnDataSourceType.NORMAL),
                  "firstname", null, false)
          ),
          List.of()
      );

      var script = new NestScript(List.of(captureStmt, insertStmt));
      assertNotNull(ScriptRunner.run(script, database.jdbcProperties()));

      assertEquals(0, database.queryInt("select count(*) from audit"));
    }
  }
}
