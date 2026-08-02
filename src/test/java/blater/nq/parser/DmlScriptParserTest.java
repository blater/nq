package blater.nq.parser;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.parser.ScriptParser;
import blater.nq.outputwriter.OutputType;
import blater.nq.parser.script.ErrorBehaviourType;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.NestSqlStatementType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DmlScriptParserTest {
  @Test
  void parsesHandlersOntoPreviousStatement() throws Exception {
    NestScript script = ScriptParser.parse("""
        update menu
        set dishname = {message.dish.dishname}
        where dishid = {message.dish.@id}
        \\g
        onError('bad update', abort, rollback)
        \\g
        onWarning('no rows', abort)
        \\g
        """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(ErrorBehaviourType.FAIL, statement.getErrorHandling().getOnErrorBehaviour());
    assertTrue(statement.getErrorHandling().isOnErrorRollback());
    assertEquals(ErrorBehaviourType.FAIL, statement.getErrorHandling().getOnProblemBehaviour());
    assertFalse(statement.getErrorHandling().isOnProblemRollback());
  }

  @Test
  void parsesLiteralSelectProcedureAndSqlLikeDmlStatements() throws Exception {
    NestScript script = ScriptParser.parse("""
        select personid from person where firstname = '${message.person.lookup}'
        \\g
        insert into audit_log (personid) values (${PERSONID})
        \\g
        execute procedure update_person(firstname = {person.firstname})
        \\g
        update person
        set lastupdated = {person.lastUpdated},
            status = {person.status}
        where personid = {person.id}
        \\g
        """);

    assertEquals(4, script.statements().size());

    assertEquals(NestSqlStatementType.SELECT, script.statements().get(0).getType());
    assertEquals(
        "select personid from person where firstname = '${message.person.lookup}'",
        script.statements().get(0).getSql());

    assertEquals(NestSqlStatementType.LITERAL, script.statements().get(1).getType());
    assertEquals("insert into audit_log (personid) values (${PERSONID})",
        script.statements().get(1).getSql());

    assertEquals(NestSqlStatementType.PROC, script.statements().get(2).getType());
    assertEquals("update_person", script.statements().get(2).getTargetName());
    assertEquals("/person/firstname", script.statements().get(2).getMappings().getFirst().xpathMapping());

    NestStatement update = script.statements().get(3);
    assertEquals("lastupdated", update.getMappings().get(0).columnDefinition().sqlName());
    assertEquals("/person/lastUpdated", update.getMappings().get(0).xpathMapping());
    assertEquals("personid", update.getMappings().get(2).columnDefinition().sqlName());
    assertTrue(update.getMappings().get(2).columnDefinition().key());
  }

  @Test
  void parsesSemicolonDelimitedStatementsWithoutGo() throws Exception {
    NestScript script = ScriptParser.parse("""
        select personid from person where firstname = '${message.person.lookup}';
        insert into audit_log (personid) values (${PERSONID});
        autocommit off;
        """);

    assertEquals(3, script.statements().size());
    assertEquals(NestSqlStatementType.SELECT, script.statements().get(0).getType());
    assertEquals("select personid from person where firstname = '${message.person.lookup}'",
        script.statements().get(0).getSql());
    assertEquals(NestSqlStatementType.LITERAL, script.statements().get(1).getType());
    assertEquals("insert into audit_log (personid) values (${PERSONID})",
        script.statements().get(1).getSql());
    assertEquals(NestSqlStatementType.AUTOCOMMIT, script.statements().get(2).getType());
  }

  @Test
  void parsesFirstOutputDirectiveAndExcludesDirectivesFromStatements() throws Exception {
    NestScript script = ScriptParser.parse("""
        output json;
        select 1 into {result.value};
        output xml;
        autocommit off;
        """);

    assertEquals(OutputType.JSON, script.outputType());
    assertEquals(2, script.statements().size());
    assertEquals(NestSqlStatementType.SELECT, script.statements().get(0).getType());
    assertEquals(NestSqlStatementType.AUTOCOMMIT, script.statements().get(1).getType());
  }

  @Test
  void parsesOutputDirectiveCaseInsensitively() throws Exception {
    NestScript script = ScriptParser.parse("""
        OuTpUt YaMl;
        select 1 into {result.value};
        """);

    assertEquals(OutputType.YAML, script.outputType());
  }

  @Test
  void parsesMarkdownOutputDirective() throws Exception {
    NestScript script = ScriptParser.parse("""
        output markdown;
        select 1 into {result.value};
        """);

    assertEquals(OutputType.MARKDOWN, script.outputType());
  }

  @Test
  void parsesJsonLinesOutputDirective() throws Exception {
    NestScript script = ScriptParser.parse("""
        output jsonl;
        select 1 into {result.value};
        """);

    assertEquals(OutputType.JSONL, script.outputType());
  }

  @Test
  void parsesTabSeparatedOutputDirective() throws Exception {
    NestScript script = ScriptParser.parse("""
        output tsv;
        select 1 into {result.value};
        """);

    assertEquals(OutputType.TSV, script.outputType());
  }

  @Test
  void parsesTomlOutputDirective() throws Exception {
    NestScript script = ScriptParser.parse("""
        output toml;
        select 1 into {result.value};
        """);

    assertEquals(OutputType.TOML, script.outputType());
  }

  @Test
  void parsesCatalogStatement() throws Exception {
    NestScript script = ScriptParser.parse("""
        output json;
        catalog;
        """);

    assertEquals(OutputType.JSON, script.outputType());
    assertEquals(1, script.statements().size());
    assertEquals(NestSqlStatementType.CATALOG, script.statements().getFirst().getType());
    assertNull(script.statements().getFirst().getCatalogPattern());
  }

  @Test
  void parsesCatalogTablePatterns() throws Exception {
    NestScript script = ScriptParser.parse("""
        catalog person;
        catalog t*;
        catalog *;
        """);

    assertEquals("person", script.statements().get(0).getCatalogPattern());
    assertEquals("t*", script.statements().get(1).getCatalogPattern());
    assertEquals("*", script.statements().get(2).getCatalogPattern());
  }

  @Test
  void parsesSqlLikeDmlStatementsWithExpressionMappings() throws Exception {
    NestScript script = ScriptParser.parse("""
        update person
        set firstname = upper({person.firstname}),
            lastname = coalesce({person.lastname}, 'Unknown')
        where personid = {person.id} and tenantid = 12
        \\g

        insert into audit_log (personid, status, nickname)
        values ({person.id}, 'NEW', defaultValue({person.nickname}, 'none'))
        \\g

        delete from audit_log
        where personid = {person.id}
        \\g
        """);

    assertEquals(3, script.statements().size());

    NestStatement update = script.statements().get(0);
    assertEquals(NestSqlStatementType.UPDATE, update.getType());
    assertEquals("person", update.getTargetName());
    assertEquals(4, update.getMappings().size());
    assertEquals("firstname", update.getMappings().get(0).columnDefinition().sqlName());
    assertEquals("/person/firstname", update.getMappings().get(0).xpathMapping());
    assertEquals("upper(${0})", update.getMappings().get(0).columnDefinition().sqlFunction());
    assertEquals("lastname", update.getMappings().get(1).columnDefinition().sqlName());
    assertEquals("coalesce(${0}, 'Unknown')", update.getMappings().get(1).columnDefinition().sqlFunction());
    assertEquals("personid", update.getMappings().get(2).columnDefinition().sqlName());
    assertTrue(update.getMappings().get(2).columnDefinition().key());
    assertEquals("tenantid", update.getMappings().get(3).columnDefinition().sqlName());
    assertEquals("12", update.getMappings().get(3).xpathMapping());

    NestStatement insert = script.statements().get(1);
    assertEquals(NestSqlStatementType.INSERT, insert.getType());
    assertEquals("NEW", insert.getMappings().get(1).xpathMapping());
    assertEquals("defaultValue(${0}, 'none')", insert.getMappings().get(2).columnDefinition().sqlFunction());

    NestStatement delete = script.statements().get(2);
    assertEquals(NestSqlStatementType.DELETE, delete.getType());
    assertTrue(delete.getMappings().getFirst().columnDefinition().key());
  }

  @Test
  void parsesReturnsClauseOnInsertAndUpdate() throws Exception {
    NestScript script = ScriptParser.parse("""
        insert into person (firstname)
        values ({person.firstname})
        returns personid into {person.id}
        \\g

        update person
        set firstname = {person.firstname}
        where personid = {person.id}
        returns lastupdated into {person.lastUpdated},
                version into {person.version}
        \\g
        """);

    NestStatement insert = script.statements().get(0);
    assertEquals(1, insert.getReturnMappings().size());
    assertEquals("personid", insert.getReturnMappings().getFirst().getColumnName());
//    assertEquals("person.id", insert.getReturnMappings().getFirst().getSourceText());
    assertEquals("/person/id", insert.getReturnMappings().getFirst().getXpath());

    NestStatement update = script.statements().get(1);
    assertEquals(2, update.getReturnMappings().size());
    assertEquals("lastupdated", update.getReturnMappings().get(0).getColumnName());
    assertEquals("/person/lastUpdated", update.getReturnMappings().get(0).getXpath());
    assertEquals("version", update.getReturnMappings().get(1).getColumnName());
    assertEquals("/person/version", update.getReturnMappings().get(1).getXpath());
  }

  @Test
  void rejectsRemovedLegacyDmlSyntaxAndMappingOptions() {
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            update table menu using (insert)
            set dishid = {message.dish.@id, key:1}
            \\g
            """));
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            execute procedure update_person(personid = {person.id, key:1})
            \\g
            """));
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            execute procedure update_person(personid = {person.id, uid:true})
            \\g
            """));
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            execute procedure update_person(personid = {person.id, autoincrement:true})
            \\g
            """));
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            execute procedure update_person(lastupdated = {person.lastUpdated, volatile:true})
            \\g
            """));
  }

  @Test
  void rejectsReturningAndDeleteReturns() {
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            insert into person (firstname)
            values ({person.firstname})
            returning personid into {person.id}
            \\g
            """));
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            delete from person
            where personid = {person.id}
            returns personid into {person.id}
            \\g
            """));
  }

  @Test
  void parsesCaptureStatementToTempStatement() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            capture 'people_snap'
            select personid, firstname from person order by personid
            \\g
            """);

    assertEquals(1, script.statements().size());
    NestStatement stmt = script.statements().getFirst();
    assertEquals(NestSqlStatementType.CAPTURE, stmt.getType());
    assertEquals("people_snap", stmt.getTargetName());
    assertEquals("select personid, firstname from person order by personid", stmt.getSql());
    assertTrue(stmt.getMappings().isEmpty());
  }

  @Test
  void parsesFromTempSqlLikeDmlSourcesAsTempRowFields() throws Exception {
    NestScript script = ScriptParser.parse("""
        update audit from temp 'people_snap'
        set myid = {personid}
        where auditid = {people.person.id}
        \\g
        """);

    NestStatement stmt = script.statements().getFirst();

    assertEquals("people_snap", stmt.getSourceRowsetName());
    assertEquals("myid", stmt.getMappings().get(0).columnDefinition().sqlName());
    assertEquals("personid", stmt.getMappings().get(0).xpathMapping());
    assertEquals("auditid", stmt.getMappings().get(1).columnDefinition().sqlName());
    assertEquals("people.person.id", stmt.getMappings().get(1).xpathMapping());
  }

  @Test
  void requiresTerminatorAfterHandler() {
    assertThrows(HiqlSyntaxException.class,
        () -> ScriptParser.parse("""
            update menu
            set dishname = {message.dish.dishname}
            where dishid = {message.dish.@id}
            \\g
            onError('bad update', abort, rollback)
            """));
  }

  @Test
  void handlersAttachToSelectAndMappedSelectStatements() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select personid from person
            \\g
            onError('keep going')
            \\g
            select personid, firstname into {people.person.firstname}
            from person
            order by personid asc createsNew {people.person}
            \\g
            onWarning('ambiguous row', rollback)
            \\g
            """);

    assertEquals(2, script.statements().size());
    NestStatement propertySelect = script.statements().get(0);
    assertEquals(NestSqlStatementType.SELECT, propertySelect.getType());
    assertEquals(ErrorBehaviourType.BEST_EFFORT, propertySelect.getErrorHandling().getOnErrorBehaviour());
    assertFalse(propertySelect.getErrorHandling().isOnErrorRollback());

    NestStatement selectStatement = script.statements().get(1);
    assertEquals(NestSqlStatementType.SELECT, selectStatement.getType());
    assertEquals(ErrorBehaviourType.BEST_EFFORT, selectStatement.getErrorHandling().getOnProblemBehaviour());
    assertTrue(selectStatement.getErrorHandling().isOnProblemRollback());
  }

  @Test
  void handlersCanBeTerminatedBySemicolonWithoutGo() throws Exception {
    NestScript script = ScriptParser.parse("""
        update menu
        set dishname = {message.dish.dishname}
        where dishid = {message.dish.@id};
        onError('bad update', abort, rollback);
        onWarning('no rows', abort);
        """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(ErrorBehaviourType.FAIL, statement.getErrorHandling().getOnErrorBehaviour());
    assertTrue(statement.getErrorHandling().isOnErrorRollback());
    assertEquals(ErrorBehaviourType.FAIL, statement.getErrorHandling().getOnProblemBehaviour());
    assertFalse(statement.getErrorHandling().isOnProblemRollback());
  }

  @Test
  void stillParsesGoTerminatorsWithOptionalSemicolon() throws Exception {
    NestScript script = ScriptParser.parse("""
        autocommit on;
        \\G
        autocommit off
        \\g
        """);

    assertEquals(2, script.statements().size());
    assertEquals("true", script.statements().get(0).getTargetName());
    assertEquals("false", script.statements().get(1).getTargetName());
  }

  @Test
  void autocommitTogglesEmitOrderedStatements() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            autocommit on
            \\g
            autocommit off
            \\g
            """);

    assertEquals(2, script.statements().size());
    assertEquals(NestSqlStatementType.AUTOCOMMIT, script.statements().get(0).getType());
    assertEquals("true", script.statements().get(0).getTargetName());
    assertEquals(NestSqlStatementType.AUTOCOMMIT, script.statements().get(1).getType());
    assertEquals("false", script.statements().get(1).getTargetName());
  }
}
