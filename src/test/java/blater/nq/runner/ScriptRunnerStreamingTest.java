package blater.nq.runner;

import blater.nq.domain.Hierarchy;
import blater.nq.outputwriter.XmlOutputWriter;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.testsupport.H2Database;
import org.jdom2.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptRunnerStreamingTest {

  @Test
  void zeroRowSelectStillEmitsTheRoot() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer primary key, firstname varchar(80))");

      NestScript script = ScriptParser.parse(
          "select\n" +
              "  personid,\n" +
              "  firstname into {people.person.firstname}\n" +
              "from person\n" +
              "where personid = -1\n" +
              "order by personid asc\n" +
              "structure {people.person} key (personid);\n");

      Hierarchy hierarchy = ScriptRunner.run(script, database.jdbcProperties());
      Document document = XmlOutputWriter.map(hierarchy);

      assertEquals("people", document.getRootElement().getName());
      assertTrue(document.getRootElement().getChildren("person").isEmpty(),
          "zero rows should yield the root with no person children");
    }
  }

  @Test
  void hierarchySelectOutput() throws Exception {
    try (H2Database database = new H2Database()) {
      NestScript script = ScriptParser.parse(
          "select max(x) into {data.value} from system_range(1, 200000);\n");

      Hierarchy hierarchy = ScriptRunner.run(script, new HashMap<>(database.jdbcProperties()));
      Document document = XmlOutputWriter.map(hierarchy);

      assertEquals("data", document.getRootElement().getName());
      assertEquals(1, document.getRootElement().getChildren("value").size(),
          "an aggregate writes one scalar value into the singleton document wrapper");
    }
  }
}
