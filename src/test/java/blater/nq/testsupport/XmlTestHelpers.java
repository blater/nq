package blater.nq.testsupport;

import blater.nq.ParameterParser;
import blater.nq.domain.Hierarchy;
import blater.nq.outputwriter.XmlOutputWriter;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.runner.ScriptRunner;
import org.jdom2.Document;
import org.jdom2.Element;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class XmlTestHelpers {
  private XmlTestHelpers() { }

  public static Element child(Element parent, String name) {
    Element child = parent.getChild(name);
    assertNotNull(child, "Expected child element " + name);
    return child;
  }

  public static List<Element> children(Element parent, String name) {
    return parent.getChildren(name);
  }

  public static void assertChildText(Element parent, String name, String expected) {
    assertEquals(expected, child(parent, name).getTextTrim());
  }

  public static List<String> childNames(Element element) {
    return element.getChildren().stream()
        .map(Element::getName)
        .toList();
  }

  /*
  ** script runners
  */
  public static Element runScriptString(H2Database database, String script, String inputXml) throws Exception {
    NestScript parsed = ScriptParser.parse(script);
    Path tempFile = Files.createTempFile("hiql-", ".xml");
    Files.writeString(tempFile, inputXml, StandardCharsets.UTF_8);
    Element rootElement = runScript(database, parsed, Map.of(ParameterParser.INPUT_FILENAME, tempFile.toString()));
    Files.deleteIfExists(tempFile);
    return rootElement;
  }

  public static Element runScriptString(H2Database database, String script) throws Exception {
    NestScript parsed = ScriptParser.parse(script);
    return runScript(database, parsed);
  }

  public static Element runScript(H2Database database, NestScript script, Map<String, String> additionalParameters) {
    Map<String, String> parameters = new HashMap<>(database.jdbcProperties());
    parameters.putAll(additionalParameters);
    Hierarchy hierachy = ScriptRunner.run(script, parameters);
    Document document = XmlOutputWriter.map(hierachy);
    // DML-only scripts produce no output hierarchy, so there is no root element to return
    return document.hasRootElement() ? document.getRootElement() : null;
  }

  public static Element runScript(H2Database database, NestScript script) {
    return runScript(database, script, Map.of());
  }


}
