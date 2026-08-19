package blater.nql.runner.correlation;

import blater.nql.domain.Hierarchy;
import blater.nql.outputwriter.XmlOutputWriter;
import blater.nql.parser.ScriptParser;
import blater.nql.parser.script.NestScript;
import blater.nql.parser.script.NestStatement;
import blater.nql.parser.script.NestSqlStatementType;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.runner.sql.SqlRowCursor;
import blater.nql.testsupport.H2Database;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blater.nql.testsupport.XmlTestHelpers.assertChildText;
import static blater.nql.testsupport.XmlTestHelpers.children;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HierarchyMappingTest {
  @Test
  void mapsNoSchemaSqlToPresentationNeutralHierarchyAndXmlWriter() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer primary key, " +
              "firstname varchar(80), " +
              "surname varchar(80))",
          "insert into person (personid, firstname, surname) values (1, 'Alice', 'Adams')",
          "insert into person (personid, firstname, surname) values (2, 'Bob', 'Baker')");

      NestScript script = ScriptParser.parse(
          """
              select
                personid,
                firstname into {people.person.firstname},
                surname into {people.person.surname}
              from person
              order by personid asc
              structure {people.person} key (personid);
              """);

      Hierarchy hierarchy = hierarchy(database, script);
      Document document = XmlOutputWriter.map(hierarchy);

      Element people = document.getRootElement();
      assertEquals("people", people.getName());
      List<Element> persons = children(people, "person");
      assertEquals(2, persons.size());
      assertChildText(persons.get(0), "firstname", "Alice");
      assertChildText(persons.get(0), "surname", "Adams");
      assertChildText(persons.get(1), "firstname", "Bob");
      assertChildText(persons.get(1), "surname", "Baker");
    }
  }

  /**
   * Drives the streaming hierarchy build exactly as {@code ScriptRunner} does: register each
   * select's plan, then feed its cursor rows into the accumulator one at a time.
   */
  private Hierarchy hierarchy(H2Database database, NestScript script) throws Exception {
    Hierarchy accumulator = new Hierarchy();
    List<NestStatement> selects = script.statements().stream()
        .filter(s -> s.getType() == NestSqlStatementType.SELECT)
        .toList();
    for (NestStatement stmt : selects) {
      accumulator.register(stmt);
      SqlExecutor sqlExecutor = new SqlExecutor(database.jdbcProperties());
      SqlRowCursor query = sqlExecutor.query(stmt.getSql());

      while (query.next()) {
        accumulator.readRow(query.row());
      }
      query.close();
      sqlExecutor.close();
    }
    return accumulator;
  }
}
