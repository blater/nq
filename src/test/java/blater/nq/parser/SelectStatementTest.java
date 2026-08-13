package blater.nq.parser;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.NestSqlStatementType;
import blater.nq.parser.script.QueryShape;
import blater.nq.domain.HierarchyPath;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SelectStatementTest {

  @Test
  public void shouldAcceptValidWithHierarchyUnionAndStructure() {
    String sql = """
        select
          p.personId,
          p.firstName into {searchResults.person.firstname},
          p.surname into {searchResults.person.surname}
        from
          person  p
        where
          firstName like '${NAME}%'
        hierarchy union
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
          personId asc,
          nicknameId asc
        structure
          {searchResults.person} key (personId),
          {searchResults.person.nickname} key (nicknameId);
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
            order by personid asc
            structure {people.person} key (personid);
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
  public void shouldBuildSelectStatementWithUsingMetadataUnionAliasesAttributesAndStructureKeys()
      throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select using schema 'people.xsd' xmlroot = people namespace = 'urn:people'
              p.personid as pid,
              p.firstname into {people.person.@firstname},
              p.surname into {people.person.surname:append(space)}
            from person p
            hierarchy union
            select
              p.personid as pid,
              n.nicknameid as nid,
              n.nickname into {people.person.nickname}
            from person p, nickname n
            order by pid asc, nid desc
            structure
              {people.person} key (pid),
              {people.person.nickname} key (nid);
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
    assertEquals(2, plan.getKeyedPaths().size());
    assertEquals(HierarchyPath.fromDottedPath("people.person"), plan.getKeyedPaths().get(0).path());
    assertEquals(HierarchyPath.fromDottedPath("people.person.nickname"), plan.getKeyedPaths().get(1).path());
  }

  @Test
  public void shouldBuildMultipleStructureKeysForOneExpression()
      throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select
              personid,
              firstname into {people.person.firstname},
              surname into {people.audit.surname}
            from person
            order by personid asc
            structure
              {people.person} key (personid),
              {people.audit} key (personid);
            """);

    var keys = script.statements().getFirst().getPlan().getKeyedPaths();
    assertEquals(2, keys.size());
    assertEquals(HierarchyPath.fromDottedPath("people.person"), keys.get(0).path());
    assertEquals(HierarchyPath.fromDottedPath("people.audit"), keys.get(1).path());
  }

  @Test
  public void shouldRejectOldXmlSelectSyntax() {
    assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            xmlselect
            select personid from person
            ;
            """));
  }

  @Test
  public void shouldBuildPlainSelectStatementForPropertyPopulation() throws Exception {
    NestScript script = ScriptParser.parse(
        """
            select count(*) as total from person
            ;
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
            ;
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
            ;
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
            ;
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
            ;
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
            ;
            """));
  }

  @Test
  public void shouldRejectHierarchyConstructsInsideInsertSelectSource() {
    var error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            insert into audit_log (firstname)
            select firstname into {people.person.firstname} from person
            ;
            """));
  }

  @Test
  public void shouldRejectStructureInsideInsertSelectSource() {
    HiqlSyntaxException error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            insert into audit_log (personid)
            select personid from person order by personid
            structure {people.person} key (personid);
            """));
  }

  @Test
  public void shouldRejectUsingMetadataOnNonFirstHierarchyUnionBranch() {
    HiqlSyntaxException error = assertThrows(HiqlSyntaxException.class, () -> ScriptParser.parse(
        """
            select using schema 'a.xsd'
              firstname into {people.person.firstname}
            from person
            hierarchy union
            select using schema 'b.xsd'
              surname into {people.person.surname}
            from person
            ;
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
            ;
            """));
  }

  @Test
  void extractsEquivalentQueryFactsAcrossWhitespaceAndComments() {
    String compact = "select c.id into {result.customer.id}, sum(o.amount) into {result.customer.total} "
        + "from customer c left join orders o on o.customer_id=c.id group by c.id;";
    String formatted = """
        select
          c /* qualifier */ . id into {result.customer.id},
          sum(o.amount) into {result.customer.total}
        from /* source */ customer c
        left join orders o -- child
          on o.customer_id = c.id
        group by
          c . id;
        """;

    QueryShape left = ScriptParser.parse(compact).statements().getFirst()
        .getSelectBlueprint().branches().getFirst().queryShape();
    QueryShape right = ScriptParser.parse(formatted).statements().getFirst()
        .getSelectBlueprint().branches().getFirst().queryShape();

    assertEquals(left, right);
    assertEquals(List.of("customer", "orders"),
        left.baseRelations().stream().map(relation -> relation.qualifiedName().value()).toList());
    assertInstanceOf(QueryShape.KnownGrouping.class, left.grouping());
    assertEquals(QueryShape.TruthValue.YES, left.characteristics().containsAggregate());
  }

  @Test
  void preservesQuotedIdentifierPartsAndMarksDerivedRelationsUnsupported() {
    NestStatement statement = ScriptParser.parse("""
        select q."Customer.Id" into {result.customer.id}
        from (select "Customer.Id" from "Sales.Schema"."Customers") as q;
        """).statements().getFirst();
    QueryShape shape = statement.getSelectBlueprint().branches().getFirst().queryShape();
    QueryShape.ExpressionFacts expression = statement.getSelectBlueprint().branches().getFirst()
        .items().getFirst().expressionFacts();

    assertTrue(shape.hasUnsupportedSource());
    QueryShape.ReferencedRelations references = QueryShape.referencedRelations(List.of(shape));
    assertTrue(references.names().isEmpty());
    assertTrue(references.hasUnsupportedSources());
    var column = expression.directColumn().orElseThrow();
    assertEquals("q", column.qualifier().orElseThrow().value());
    assertEquals("Customer.Id", column.column().text());
    assertTrue(column.column().quoted());
  }

  @Test
  void distinguishesAbsentGroupingFromUnknownAggregateClassification() {
    QueryShape shape = ScriptParser.parse("""
        select upper(c.name) into {result.customer.name} from customer c;
        """).statements().getFirst().getSelectBlueprint().branches().getFirst().queryShape();

    assertInstanceOf(QueryShape.NoGrouping.class, shape.grouping());
    assertEquals(QueryShape.TruthValue.UNKNOWN, shape.characteristics().containsAggregate());
  }

  @Test
  void extractsCommaRelationsAndCompositeGrouping() {
    QueryShape shape = ScriptParser.parse("""
        select distinct a.tenant_id into {result.entry.tenant},
          b.entry_id into {result.entry.id}
        from account a, entry b
        where b.tenant_id = a.tenant_id
        group by a.tenant_id, b.entry_id;
        """).statements().getFirst().getSelectBlueprint().branches().getFirst().queryShape();

    assertEquals(List.of("account", "entry"),
        shape.baseRelations().stream().map(relation -> relation.qualifiedName().value()).toList());
    QueryShape.KnownGrouping grouping = assertInstanceOf(QueryShape.KnownGrouping.class, shape.grouping());
    assertEquals(2, grouping.expressions().size());
    assertTrue(grouping.expressions().stream().allMatch(expression -> expression.directColumn().isPresent()));
    assertEquals(QueryShape.TruthValue.YES, shape.characteristics().distinct());
  }

  @Test
  void marksAdvancedGroupingAsUnsupported() {
    QueryShape shape = ScriptParser.parse("""
        select department into {result.summary.department}, count(*) into {result.summary.total}
        from employee group by rollup(department);
        """).statements().getFirst().getSelectBlueprint().branches().getFirst().queryShape();

    assertInstanceOf(QueryShape.UnsupportedGrouping.class, shape.grouping());
  }
}
