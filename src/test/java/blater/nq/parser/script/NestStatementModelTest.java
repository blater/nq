package blater.nq.parser.script;

import blater.nq.parser.script.ErrorBehaviourType;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.NestSqlStatementType;
import blater.nq.domain.MappingPlan;
import blater.nq.domain.OutputField;
import blater.nq.domain.HierarchyPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NestStatementModelTest {

  @Test
  void dmlStatementCarriesTypeTargetAndMappings() {
    var stmt = NestStatement.dml(NestSqlStatementType.UPDATE, "person", null, List.of(), List.of());

    assertEquals(NestSqlStatementType.UPDATE, stmt.getType());
    assertNull(stmt.getSourceRowsetName());
    assertEquals("person", stmt.getTargetName());
    assertNull(stmt.getSql());
  }

  @Test
  void procedureStatementCarriesTarget() {
    var stmt = NestStatement.proc("update_person", List.of());

    assertEquals(NestSqlStatementType.PROC, stmt.getType());
    assertEquals("update_person", stmt.getTargetName());
  }

  @Test
  void literalStatementCarriesSql() {
    var stmt = NestStatement.literal("truncate table audit_log");

    assertEquals(NestSqlStatementType.LITERAL, stmt.getType());
    assertNull(stmt.getSourceRowsetName());
    assertNull(stmt.getTargetName());
    assertEquals("truncate table audit_log", stmt.getSql());
  }

  @Test
  void catalogStatementCarriesOptionalTablePattern() {
    var stmt = NestStatement.catalog("person*");

    assertEquals(NestSqlStatementType.CATALOG, stmt.getType());
    assertNull(stmt.getSourceRowsetName());
    assertNull(stmt.getTargetName());
    assertEquals("person*", stmt.getCatalogPattern());
    assertNull(stmt.getSql());
  }

  @Test
  void tempSourceAndCaptureStatementExpressTheCrossFlowCase() {
    var writeStmt = NestStatement.capture(
        "matched_people",
        "select personid, firstname from person where firstname like '${root.prefix}%'");

    var readStmt = NestStatement.dml(NestSqlStatementType.INSERT, "audit", "matched_people", List.of(), List.of());

    assertEquals(NestSqlStatementType.CAPTURE, writeStmt.getType());
    assertEquals("matched_people", writeStmt.getTargetName());
    assertEquals("matched_people", readStmt.getSourceRowsetName());
  }

  @Test
  void selectStatementCarriesMappingPlanAndOutputMetadata() {
    var path = HierarchyPath.fromDottedPath("people.person.@firstname");
    var field = new OutputField(path, "col1", null, List.of(), false);
    var plan = new MappingPlan(List.of(field), List.of());

    var stmt = NestStatement.select(
        "select personid, firstname from person",
        plan,
        "urn:example");

    assertEquals(NestSqlStatementType.SELECT, stmt.getType());
    assertEquals("urn:example", stmt.getNamespace());
    assertEquals(1, stmt.getPlan().getFields().size());
    assertEquals("col1", stmt.getPlan().getFields().getFirst().getSourceColumn());
    assertTrue(stmt.getPlan().getFields().getFirst().isAttribute());
    assertEquals("select personid, firstname from person", stmt.getSql());
    assertNull(stmt.getSourceRowsetName());
    assertNull(stmt.getTargetName());
  }

  @Test
  void defaultsErrorHandlingUntilHandlerIsSet() {
    var stmt = NestStatement.literal("sql");
    assertEquals(ErrorBehaviourType.FAIL, stmt.getErrorHandling().getOnProblemBehaviour());
    assertEquals(ErrorBehaviourType.FAIL, stmt.getErrorHandling().getOnErrorBehaviour());
  }
}
