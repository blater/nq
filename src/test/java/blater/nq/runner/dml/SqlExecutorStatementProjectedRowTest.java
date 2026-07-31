package blater.nq.runner.dml;
import blater.nq.runner.sql.domain.DmlExecutionResult;
import blater.nq.runner.sql.dml.RunInsert;
import blater.nq.runner.sql.dml.RunUpdate;
import blater.nq.runner.sql.domain.SqlStatement;

import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.parser.script.ErrorBehaviourType;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.ErrorStrategy;
import blater.nq.parser.script.NestSqlStatementType;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.dml.statementbuilder.InsertStatementBuilder;
import blater.nq.runner.sql.dml.statementbuilder.UpdateStatementBuilder;
import blater.nq.domain.SqlType;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static blater.nq.testsupport.PersistenceProjectionFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SqlExecutorStatementProjectedRowTest {
  @Test
  public void insertBuilderUsesProjectedRowValuesInBindOrder() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, true, false),
        column("firstname", "string", false, false, false));
    var statement = statement(NestSqlStatementType.INSERT, "person");
    SqlRow row = row(columns, null, "Fred");
    row = row.withColumnTypes(Map.of(
        "personid", SqlType.INTEGER,
        "firstname", SqlType.STRING));

    SqlStatement insert = InsertStatementBuilder.build(statement, row);

    assertEquals("insert into person (firstname) values (?)", insert.getSql());
    assertEquals(List.of("Fred"), insert.getParameters());
  }

  @Test
  public void updateBuilderUsesProjectedRowValuesInBindOrder() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false),
        column("firstname", "string", false, false, false));
    var statement = statement(NestSqlStatementType.UPDATE, "person");
    SqlRow row = row(columns, "7", "Fred");
    row = row.withColumnTypes(Map.of(
        "personid", SqlType.INTEGER,
        "firstname", SqlType.STRING));

    SqlStatement update = UpdateStatementBuilder.build(statement, row);

    assertEquals("update person set firstname = ? where personid = ?", update.getSql());
    assertEquals(List.of("Fred", 7), update.getParameters());
  }

  @Test
  public void insertRunnerExecutesProjectedRowAndReturnsGeneratedId() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute("create table person (personid integer auto_increment primary key, firstname varchar(80))");
      List<ColumnDefinition> columns = List.of(
          column("personid", "integer", true, true, false),
          column("firstname", "string", false, false, false));
      var statement = statement(NestSqlStatementType.INSERT, "person");
      SqlRow row = row(columns, null, "Fred");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      DmlExecutionResult result;
      try {
        result = RunInsert.execute(statement, row, sqlExecutor);
      } finally {
        sqlExecutor.close();
      }

      assertEquals("Fred", database.queryString("select firstname from person where personid = 1"));
      assertEquals("1", result.dbAssignedValues().get("personid"));
    }
  }

  @Test
  public void updateRunnerExecutesProjectedRow() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))",
          "insert into person (personid, firstname) values (7, 'Old')");
      List<ColumnDefinition> columns = List.of(
          column("personid", "integer", true, false, false),
          column("firstname", "string", false, false, false));
      var statement = statement(NestSqlStatementType.UPDATE, "person");
      SqlRow row = row(columns, "7", "Fred");

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try {
        RunUpdate.execute(statement, row, sqlExecutor);
      } finally {
        sqlExecutor.close();
      }

      assertEquals("Fred", database.queryString("select firstname from person where personid = 7"));
    }
  }

  @Test
  public void emptyInsertFailsByDefault() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute("create table person (personid integer auto_increment primary key)");
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, true, false));
    SqlRow row = row(columns, (String) null);

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      try {
    assertThrows(IllegalStateException.class,
            () -> RunInsert.execute(statement(NestSqlStatementType.INSERT, "person"), row, sqlExecutor));
      } finally {
        sqlExecutor.close();
      }
    }
  }

  @Test
  public void emptyInsertCanSkipInBestEffortMode() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute("create table person (personid integer auto_increment primary key)");
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, true, false));
    SqlRow row = row(columns, (String) null);

      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      DmlExecutionResult result;
      try {
        result = RunInsert.execute(statement( NestSqlStatementType.INSERT, "person",
                new ErrorStrategy( ErrorBehaviourType.BEST_EFFORT, ErrorBehaviourType.FAIL)),
                row,
                sqlExecutor);
      } finally {
        sqlExecutor.close();
      }

      assertEquals(DmlExecutionResult.EMPTY, result);
      assertEquals(0, database.queryInt("select count(*) from person"));
    }
  }

  private NestStatement statement(NestSqlStatementType type, String target) {
    return statement(type, target, new ErrorStrategy(
        ErrorBehaviourType.FAIL, ErrorBehaviourType.FAIL));
  }

  private NestStatement statement(NestSqlStatementType type, String target, ErrorStrategy handling) {
    NestStatement statement = NestStatement.dml(type, target, null, List.of(), List.of());
    statement.setErrorHandling(handling);
    return statement;
  }
}
