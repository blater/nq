package blater.nql.runner.dml.sqlbuilder;

import blater.nql.runner.sql.dml.statementbuilder.DeleteStatementBuilder;
import blater.nql.runner.sql.dml.statementbuilder.InsertStatementBuilder;
import blater.nql.runner.sql.dml.statementbuilder.ProcedureStatementBuilder;
import blater.nql.runner.sql.dml.statementbuilder.UpdateStatementBuilder;
import blater.nql.runner.sql.domain.SqlStatement;
import blater.nql.runner.SyntaxErrorType;
import blater.nql.runner.sql.domain.ColumnDefinition;
import blater.nql.runner.sql.domain.SqlRow;
import blater.nql.parser.script.NestSqlStatementType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blater.nql.testsupport.PersistenceProjectionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

public class ScriptStatementRendererTest {
  @Test
  public void rendersInsertSkippingUidAndMissingDataColumns() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, true, false),
        column("firstname", "string", false, false, false),
        column("nickname", "string", false, false, false));
    var statement =
        statement(NestSqlStatementType.INSERT, "person");
    SqlRow row = row(
        columns,
        null, "Fred", null);

    SqlStatement rendered = new InsertStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.OK, rendered.getStatus());
    assertEquals("insert into person (firstname) values (?)", rendered.getSql());
    assertEquals(List.of("Fred"), rendered.getParameters());
    assertEquals("personid", rendered.getDbAssignedIdentityColumn());
  }

  @Test
  public void rendersKeyOnlyInsert() {
    List<ColumnDefinition> columns =
        List.of(column("auditid", "integer", true, false, false));
    var statement = statement(NestSqlStatementType.INSERT, "audit");
    SqlRow row = row(columns, "7");

    SqlStatement rendered = new InsertStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.OK, rendered.getStatus());
    assertEquals("insert into audit (auditid) values (?)", rendered.getSql());
    assertEquals(List.of(7), rendered.getParameters());
  }

  @Test
  public void marksEmptyInsertAsStatementProblem() {
    List<ColumnDefinition> columns =
        List.of(column("auditid", "integer", true, true, false));
    var statement = statement(NestSqlStatementType.INSERT, "audit");
    SqlRow row = row(columns, (String) null);

    SqlStatement rendered = new InsertStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.EMPTY_INSERT, rendered.getStatus());
  }

  @Test
  public void rendersUpdateWithDbAssignedRefreshSql() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false),
        column("lastupdated", "string", false, false, true));
    var statement = statement(NestSqlStatementType.UPDATE, "person");
    SqlRow row = row(
        columns,
        "7",
        "old");

    SqlStatement rendered = new UpdateStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.OK, rendered.getStatus());
    assertEquals("update person set lastupdated = ? where personid = ?", rendered.getSql());
    assertEquals(List.of("old", 7), rendered.getParameters());
    assertEquals(
        "select lastupdated from person where personid = ?", rendered.getDbAssignedValuesSql(null));
    assertEquals(List.of(7), rendered.getDbAssignedValuesParameters(null));
    assertEquals(1, rendered.getDbAssignedColumns().size());
  }

  @Test
  public void appendsGeneratedKeyToDbAssignedRefreshSqlWithoutMarkerPlaceholder() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, true, false),
        column("lastupdated", "string", false, false, true));
    var statement = statement(NestSqlStatementType.INSERT, "person");
    SqlRow row = row(columns, null, "old");

    SqlStatement rendered = new InsertStatementBuilder().build(statement, row);

    assertEquals("select lastupdated from person", rendered.getDbAssignedValuesSql(null));
    assertEquals(List.of(), rendered.getDbAssignedValuesParameters(null));
    assertEquals(
        "select lastupdated from person where personid = ?",
        rendered.getDbAssignedValuesSql("42"));
    assertEquals(List.of("42"), rendered.getDbAssignedValuesParameters("42"));
  }

  @Test
  public void rendersDeleteFromKeyColumnsOnly() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false),
        column("firstname", "string", false, false, false));
    var statement = statement(NestSqlStatementType.DELETE, "person");
    SqlRow row = row(columns, "7", "Fred");

    SqlStatement rendered = DeleteStatementBuilder.build(statement, row);

    assertEquals(SyntaxErrorType.OK, rendered.getStatus());
    assertEquals("delete from person where personid = ?", rendered.getSql());
    assertEquals(List.of(7), rendered.getParameters());
  }

  @Test
  public void marksUpdateMissingKeyAsStatementProblem() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false),
        column("firstname", "string", false, false, false));
    var statement = statement(NestSqlStatementType.UPDATE, "person");
    SqlRow row = row(columns, null, "Fred");

    SqlStatement rendered = new UpdateStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.UPDATE_MISSING_KEY, rendered.getStatus());
  }

  @Test
  public void marksUpdateWithNoValuesAsStatementProblem() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false),
        column("firstname", "string", false, false, false));
    var statement = statement(NestSqlStatementType.UPDATE, "person");
    SqlRow row = row(columns, "7", null);

    SqlStatement rendered = new UpdateStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.UPDATE_NO_VALUES, rendered.getStatus());
  }

  @Test
  public void marksDeleteMissingKeyAsStatementProblem() {
    List<ColumnDefinition> columns = List.of(
        column("personid", "integer", true, false, false));
    var statement = statement(NestSqlStatementType.DELETE, "person");
    SqlRow row = row(columns, (String) null);

    SqlStatement rendered = DeleteStatementBuilder.build(statement, row);

    assertEquals(SyntaxErrorType.DELETE_MISSING_KEY, rendered.getStatus());
  }

  @Test
  public void rendersProcedureSkippingMissingDataColumns() {
    List<ColumnDefinition> columns = List.of(
        column("firstname", "string", false, false, false),
        column("nickname", "string", false, false, false));
    var statement =
        procedureStatement("update_person");
    SqlRow row = row(columns, "Fred", null);

    SqlStatement rendered = new ProcedureStatementBuilder().build(statement, row);

    assertEquals(SyntaxErrorType.OK, rendered.getStatus());
    assertEquals("execute procedure update_person(firstname = ?)", rendered.getSql());
    assertEquals(List.of("Fred"), rendered.getParameters());
  }
}
