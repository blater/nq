package blater.nql.outputwriter;

import blater.nql.execution.EngineParameterNames;
import blater.nql.parser.script.NestScript;
import blater.nql.parser.script.NestStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputTypeTest {
  @Test
  void scriptsDefaultToJson() {
    NestScript catalogScript = new NestScript(List.of(NestStatement.catalog(null)));

    assertEquals(OutputType.JSON, OutputType.get(catalogScript, Map.of()));
  }

  @Test
  void commandLineOutputOverridesTheScriptDirective() {
    NestScript yamlScript = new NestScript(OutputType.YAML, List.of());
    Map<String, String> commandLineJson = Map.of(EngineParameterNames.OUTPUT_TYPE, "json");

    assertEquals(OutputType.YAML, OutputType.get(yamlScript, Map.of()));
    assertEquals(OutputType.JSON, OutputType.get(yamlScript, commandLineJson));
  }

  @Test
  void selectsJsonLinesByNameCaseInsensitively() {
    assertEquals(OutputType.JSONL, OutputType.fromName("jsonl"));
    assertEquals(OutputType.JSONL, OutputType.fromName("JSONL"));
  }

  @Test
  void selectsTabSeparatedOutputByNameCaseInsensitively() {
    assertEquals(OutputType.TSV, OutputType.fromName("tsv"));
    assertEquals(OutputType.TSV, OutputType.fromName("TSV"));
  }

  @Test
  void selectsTomlOutputByNameCaseInsensitively() {
    assertEquals(OutputType.TOML, OutputType.fromName("toml"));
    assertEquals(OutputType.TOML, OutputType.fromName("TOML"));
  }
}
