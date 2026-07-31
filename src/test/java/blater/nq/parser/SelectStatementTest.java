package blater.nq.parser;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.NestSqlStatementType;
import blater.nq.domain.HierarchyPath;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SelectStatementTest {

  @Test
  public void shouldAcceptValidWithXmlUnion() {
    String sql = """
        select
          p.personId,
          p.firstName into {searchResults.person.firstname},
          p.surname into {searchResults.person.surname}
        from
          person  p
        where
          firstName like '${NAME}%'
        xmlunion
        select
          n.personId,
          n.nicknameId,
          p.firstName into {searchResults.person.firstname},
          p.surname into {searchResults.person.surname},
          n.nickname into {searchResults.person.nickname}
        from
          person   p,
          nickname n
        where
          p.personId    = n.personId
        and n.nickname like '${NAME}%'
        order by
          surname asc,
          personId   asc createsNew {searchResults.person},
          nicknameId asc createsNew {searchResults.person.nickname}
        \\G
        """;
    assertDoesNotThrow(() -> ScriptParser.parse(sql));
  }

  @Test
  public void shouldBuildAsPathMappingAliasesWithoutSendingPathToSql()
      throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select
              personid,
              case when category = 'A' then firstname end into {people.person.staffName},
              case when category != 'A' then firstname end into {people.person.contractorName},
              case when surname not like 'TEMP%' escape '$' then firstname end into {people.person.smithName} absent on null
            from person
            where active = true
            order by personid asc createsNew {people.person}
            \\G
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());

    assertTrue(statement.getSql().contains("case when category = 'A' then firstname end as"));
    assertTrue(statement.getSql().contains("category != 'A'"));
    assertTrue(statement.getSql().contains("surname not like 'TEMP%' escape '$'"));
    assertTrue(statement.getSql().contains("where active = true"));
    assertFalse(statement.getSql().contains("{people.person"));

    var fields = statement.getPlan().getFields();
    assertEquals(3, fields.size());
    assertEquals(HierarchyPath.fromDottedPath("people.person.staffName"), fields.get(0).getPath());
    assertFalse(fields.get(0).isAbsentOnNull());
    assertEquals(HierarchyPath.fromDottedPath("people.person.contractorName"), fields.get(1).getPath());
    assertFalse(fields.get(1).isAbsentOnNull());
    assertEquals(HierarchyPath.fromDottedPath("people.person.smithName"), fields.get(2).getPath());
    assertTrue(fields.get(2).isAbsentOnNull());
  }

  @Test
  public void shouldBuildSelectStatementWithUsingMetadataUnionAliasesAttributesAndCreatesNewRules()
      throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select using schema 'people.xsd' xmlroot = people namespace = 'urn:people'
              p.personid as pid,
              p.firstname into {people.person.@firstname},
              p.surname into {people.person.surname:append(space)}
            from person p
            xmlunion
            select
              p.personid as pid,
              n.nicknameid as nid,
              n.nickname into {people.person.nickname}
            from person p, nickname n
            order by pid asc createsNew {people.person},
              nid desc createsNew {people.person.nickname}
            \\G
            """);

    assertEquals(1, script.statements().size());
    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());

    assertEquals("urn:people", statement.getNamespace());
    assertTrue(statement.getSql().contains(" union "));
    assertTrue(statement.getSql().contains("hiql_select_branch"));
    assertTrue(statement.getSql().contains("order by \"col1\" asc, \"col2\" desc"));

    var plan = statement.getPlan();
    assertEquals(3, plan.getFields().size());
    var surname = plan.getFields().stream()
        .filter(field -> field.getPath().equals(HierarchyPath.fromDottedPath("people.person.surname")))
        .findFirst().orElseThrow();
    var firstname = plan.getFields().stream()
        .filter(field -> field.getPath().equals(HierarchyPath.fromDottedPath("people.person.@firstname")))
        .findFirst().orElseThrow();
    var nickname = plan.getFields().stream()
        .filter(field -> field.getPath().equals(HierarchyPath.fromDottedPath("people.person.nickname")))
        .findFirst().orElseThrow();
    assertEquals(" ", surname.getAppendText());
    assertTrue(firstname.isAttribute());
    assertNotNull(surname.getSourceColumn());
    assertNotNull(firstname.getSourceColumn());
    assertNotNull(nickname.getSourceColumn());
    assertEquals(2, plan.getCorrelationRules().size());
    assertEquals(HierarchyPath.fromDottedPath("people.person"), plan.getCorrelationRules().get(0).getPath());
    assertEquals(HierarchyPath.fromDottedPath("people.person.nickname"), plan.getCorrelationRules().get(1).getPath());
  }

  @Test
  public void shouldBuildMultipleCreatesNewRulesForOneOrderItem()
      throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select
              personid,
              firstname into {people.person.firstname},
              surname into {people.audit.surname}
            from person
            order by personid asc
              createsNew {people.person}
              & createsNew {people.audit}
            \\G
            """);

    var rules = script.statements().getFirst().getPlan().getCorrelationRules();
    assertEquals(2, rules.size());
    assertEquals(HierarchyPath.fromDottedPath("people.person"), rules.get(0).getPath());
    assertEquals("col1", rules.get(0).getConditions().getFirst().fieldName());
    assertEquals(HierarchyPath.fromDottedPath("people.audit"), rules.get(1).getPath());
    assertEquals("col1", rules.get(1).getConditions().getFirst().fieldName());
  }

  @Test
  public void shouldRejectOldXmlSelectSyntax() {
    assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            xmlselect
            select personid from person
            \\g
            """));
  }

  @Test
  public void shouldBuildPlainSelectStatementForPropertyPopulation() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select count(*) as total from person
            \\g
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());
    assertTrue(statement.getPlan().getFields().isEmpty());
    assertEquals("select count(*) as total from person", statement.getSql());
  }

  @Test
  public void shouldKeepSqlColumnAliasUnchanged() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select colname as colalias from t
            \\g
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());
    assertTrue(statement.getPlan().getFields().isEmpty());
    assertEquals("select colname as colalias from t", statement.getSql());
  }

  @Test
  public void shouldForwardSelectIntoTableAsPassthroughSql() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select x into newtable from t
            \\g
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());
    assertTrue(statement.getPlan().getFields().isEmpty());
    assertEquals("select x into newtable from t", statement.getSql());
  }

  @Test
  public void shouldBuildSqlAliasAndMappingTargetOnSameItem() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select name as personName into {person.name} from person
            \\g
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.SELECT, statement.getType());

    var fields = statement.getPlan().getFields();
    assertEquals(1, fields.size());
    assertEquals(HierarchyPath.fromDottedPath("person.name"), fields.getFirst().getPath());

    assertFalse(statement.getSql().contains("{person.name}"));
  }

  @Test
  public void shouldParseExplicitLiteralSqlAndStripLiteralKeyword() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            literal create table audit_log (message varchar(80))
            \\g
            """);

    NestStatement statement = script.statements().getFirst();
    assertEquals(NestSqlStatementType.LITERAL, statement.getType());
    assertEquals("create table audit_log (message varchar(80))", statement.getSql());
  }

  @Test
  public void shouldRejectUsingMetadataWithoutHierarchyMappings() {
    var error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            select using schema 'x' xmlroot = y count(*) from t
            \\g
            """));
  }

  @Test
  public void shouldRejectHierarchyConstructsInsideInsertSelectSource() {
    var error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            insert into audit_log (firstname)
            select firstname into {people.person.firstname} from person
            \\g
            """));
  }

  @Test
  public void shouldRejectCreatesNewInsideInsertSelectSource() {
    HiqlSyntaxException error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            insert into audit_log (personid)
            select personid from person order by personid createsNew {people.person}
            \\g
            """));
  }

  @Test
  public void shouldRejectUsingMetadataOnNonFirstXmlUnionBranch() {
    HiqlSyntaxException error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            select using schema 'a.xsd'
              firstname into {people.person.firstname}
            from person
            xmlunion
            select using schema 'b.xsd'
              surname into {people.person.surname}
            from person
            \\g
            """));

    assertTrue(error.getMessage().contains("only valid on the first"));
  }

  @Test
  public void shouldParseComplexSqlClausesInSelectStatement() throws Exception {
    assertDoesNotThrow(() -> ScriptParser.parse(
        """
            select department, upper(surname) as surname_key, count(*) as total
            from person
            group by department, upper(surname), year(created_at)
            having count(*) > 1 and max(score) >= 10
            order by upper(surname) asc, 1 desc
            \\g
            """));
  }
}
