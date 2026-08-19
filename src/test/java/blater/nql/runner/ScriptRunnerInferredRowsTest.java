package blater.nql.runner;

import blater.nql.execution.EngineParameterNames;
import blater.nql.domain.Hierarchy;
import blater.nql.outputwriter.XmlOutputWriter;
import blater.nql.parser.ScriptParser;
import blater.nql.parser.script.NestScript;
import blater.nql.testsupport.H2Database;
import org.jdom2.Document;
import org.jdom2.Element;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptRunnerInferredRowsTest {
  @Test
  void ambiguousRowContextFailsByDefault() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person_phone (" +
              "personid integer, " +
              "phone_number varchar(20), " +
              "phone_label varchar(20))");

      IllegalStateException thrown = assertThrows(
          IllegalStateException.class,
          () -> run(database, ambiguousScript(), ambiguousInput()));

      assertEquals(
          "DML statement error: AMBIGUOUS_ROW_CONTEXT",
          thrown.getMessage());
      assertEquals(0, database.queryInt("select count(*) from person_phone"));
    }
  }

  @Test
  void ambiguousRowContextCanSkipInBestEffortMode() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person_phone (" +
              "personid integer, " +
              "phone_number varchar(20), " +
              "phone_label varchar(20))");

      run(database, ambiguousScript() + "onWarning('ambiguous');\n", ambiguousInput());

      assertEquals(0, database.queryInt("select count(*) from person_phone"));
    }
  }

  @Test
  void generatedParentIdsWriteToCorrectRepeatedRowsInInferredMode() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (" +
              "personid integer auto_increment primary key, " +
              "firstname varchar(80))",
          "create table nickname (" +
              "nicknameid integer primary key, " +
              "personid integer, " +
              "nickname varchar(80))");

      String inputXml = "<message>" +
          "<person>" +
          "<firstname>Fred</firstname>" +
          "<nickname id=\"10\">Ace</nickname>" +
          "</person>" +
          "<person>" +
          "<firstname>Wilma</firstname>" +
          "<nickname id=\"11\">Wills</nickname>" +
          "</person>" +
          "</message>";

      NestScript parsed = ScriptParser.parse(
          """
              insert into person (firstname)
              values ({message.person.firstname})
              returns personid into {message.person.id}
              ;
              insert into nickname (nicknameid, personid, nickname)
              values ({message.person.nickname.@id}, {message.person.id}, {message.person.nickname})
              ;
              """);

      Document returned = run(database, new NestScript(parsed.statements()), inputXml);

      Element root = returned.getRootElement();
      assertEquals("1", root.getChildren("person").get(0).getChildTextTrim("id"));
      assertEquals("2", root.getChildren("person").get(1).getChildTextTrim("id"));
      assertEquals(1, database.queryInt("select personid from nickname where nicknameid = 10"));
      assertEquals(2, database.queryInt("select personid from nickname where nicknameid = 11"));
    }
  }

  private void run(H2Database database, String scriptText, String inputXml) throws Exception {
    run(database, new NestScript(ScriptParser.parse(scriptText).statements()), inputXml);
  }

  private Document run(H2Database database, NestScript script, String inputXml) throws Exception {
    Path tempFile = Files.createTempFile("hiql-", ".xml");
    try {
      Files.writeString(tempFile, inputXml, StandardCharsets.UTF_8);
      Map<String, String> params = new HashMap<>(database.jdbcProperties());
      params.put(EngineParameterNames.INPUT_FILENAME, tempFile.toString());
      Hierarchy hierarchy = ScriptRunner.run(script, params);
      return XmlOutputWriter.map(hierarchy);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  private String ambiguousScript() {
    return "insert into person_phone (personid, phone_number, phone_label)\n"
        + "values ({person.@id}, {person.phone}, {person.phoneLabel})\n"
        + ";\n";
  }

  private String ambiguousInput() {
    return "<person id=\"7\">"
        + "<phone>111</phone>"
        + "<phone>222</phone>"
        + "<phoneLabel>home</phoneLabel>"
        + "<phoneLabel>work</phoneLabel>"
        + "</person>";
  }
}
