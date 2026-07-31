package blater.nq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterializationOptionsTest {
  @TempDir
  Path tempDir;

  @Test
  void parsesModeAndRepeatableAliasesInBothValueForms() throws Exception {
    Path script = write("query.nq", "select 1;");
    Path input = write("input.json", "{}");

    var parameters = ParameterParser.parse(
        script.toString(), input.toString(),
        "--anonymous-collections=ERROR",
        "--relation-alias", "/0=customers",
        "--relation-alias=/1=products");

    assertEquals("ERROR", parameters.get(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM));
    assertEquals("/0=customers", parameters.get(ParameterParser.RELATION_ALIAS_PREFIX + "000000"));
    assertEquals("/1=products", parameters.get(ParameterParser.RELATION_ALIAS_PREFIX + "000001"));
  }

  @Test
  void rejectsInvalidValuesAndContexts() throws Exception {
    Path script = write("query.nq", "select 1;");
    Path json = write("input.json", "{}");
    Path xml = write("input.xml", "<input/>");

    assertThrows(IllegalArgumentException.class,
        () -> ParameterParser.parse(script.toString(), json.toString(),
            "--anonymous-collections", "separate"));
    assertThrows(IllegalArgumentException.class,
        () -> ParameterParser.parse(script.toString(), "--relation-alias", "/=records"));
    assertThrows(IllegalArgumentException.class,
        () -> ParameterParser.parse("--list-caches", "--anonymous-collections", "error"));
    assertThrows(IllegalArgumentException.class,
        () -> ParameterParser.parse(script.toString(), xml.toString(),
            "--relation-alias", "/=records"));
    assertThrows(IllegalArgumentException.class,
        () -> ParameterParser.parse(script.toString(), json.toString(),
            "--jdbc-database", "jdbc:h2:mem:test", "--relation-alias", "/=records"));
  }

  @Test
  void useCacheBySourceAcceptsMaterializationSelectors() {
    var parameters = ParameterParser.parse(
        "--use-cache", "input.json",
        "--anonymous-collections", "error",
        "--relation-alias", "/=records");

    assertEquals("input.json", parameters.get(ParameterParser.CACHE_USE_PARAM));
    assertEquals("error", parameters.get(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM));
  }

  @Test
  void propertiesCannotSetPrivateMaterializationParameters() {
    var parameters = new LinkedHashMap<String, String>();

    ParameterParser.addParameterFromMainPropsFile(
        parameters, ParameterParser.ANONYMOUS_COLLECTIONS_PARAM + "=error");
    ParameterParser.addParameterFromMainPropsFile(
        parameters, ParameterParser.RELATION_ALIAS_PREFIX + "000000=/=records");

    assertFalse(parameters.containsKey(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM));
    assertEquals(0, parameters.size());
  }

  private Path write(String name, String content) throws Exception {
    Path file = tempDir.resolve(name);
    Files.writeString(file, content);
    return file;
  }
}
