package blater.nq.execution;

import blater.nq.cli.DataInput;
import blater.nq.cli.DataSourceSpec;
import blater.nq.cli.InputSelection;
import blater.nq.cli.RunInvocation;
import blater.nq.cli.parse.CliParser;
import blater.nq.inputreader.InputType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EngineRuntimeParametersTest {
  private final CliParser parser = new CliParser(Map.of(), Path.of("/tmp/nq-test-home"));

  @Test
  void mapsOnlyEngineValuesFromATypedRun() {
    Map<String, String> parameters = EngineRuntimeParameters.from(parser.parse(
        "query.nq", "customers.JSON", "-o", "yaml", "--debug",
        "--no-key-inference", "--param", "region=EMEA"));

    assertEquals("customers.JSON", parameters.get(EngineParameterNames.INPUT_FILENAME));
    assertEquals("json", parameters.get(EngineParameterNames.INPUT_TYPE));
    assertEquals("yaml", parameters.get(EngineParameterNames.OUTPUT_TYPE));
    assertEquals("true", parameters.get(EngineParameterNames.DEBUG));
    assertEquals("true", parameters.get(EngineParameterNames.NO_KEY_INFERENCE));
    assertEquals("EMEA", parameters.get("region"));
    assertFalse(parameters.containsKey("command"));
    assertFalse(parameters.containsKey("cache.dir"));
  }

  @Test
  void cacheSelectionStaysTypedAndDoesNotLeakIntoTheEngineMap() {
    Map<String, String> parameters = EngineRuntimeParameters.from(withoutAutomaticInput(
        (RunInvocation) parser.parse(
            "query.nq", "--cache", "--name", "customers",
            "--cache-dir", "/tmp/nq-cache")));

    assertFalse(parameters.containsKey("cache.dir"));
    assertFalse(parameters.containsKey("NSQL_CACHE"));
  }

  @Test
  void mapsUrlOnlyJdbcWithoutInventingADriverHint() {
    Map<String, String> parameters = EngineRuntimeParameters.from(withoutAutomaticInput(
        (RunInvocation) parser.parse(
            "query.nq", "--jdbc-database", "jdbc:example:test")));

    assertEquals("jdbc:example:test", parameters.get(EngineParameterNames.JDBC_DATABASE));
    assertFalse(parameters.containsKey(EngineParameterNames.JDBC_DRIVER));
    assertFalse(parameters.containsKey(EngineParameterNames.JDBC_CLASS_NAME));
  }

  @Test
  void literalDataRequiresItsExactMaterializedPath() {
    DataInput input = new DataInput(new DataSourceSpec.Text("{\"id\":1}"), InputType.JSON);
    var invocation = parser.parse("select id from item;", "{\"id\":1}");

    assertThrows(IllegalStateException.class, () -> EngineRuntimeParameters.from(invocation));

    Map<String, String> parameters = EngineRuntimeParameters.from(
        invocation, new MaterializedDataInput(input, Path.of("/tmp/staged.json")));
    assertEquals("/tmp/staged.json", parameters.get(EngineParameterNames.INPUT_FILENAME));
    assertEquals("json", parameters.get(EngineParameterNames.INPUT_TYPE));
  }

  private static RunInvocation withoutAutomaticInput(RunInvocation invocation) {
    return new RunInvocation(
        invocation.script(), new InputSelection.None(), invocation.target(), invocation.output(),
        invocation.noKeyInference(), invocation.options());
  }
}
