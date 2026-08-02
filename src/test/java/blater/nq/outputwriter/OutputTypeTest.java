package blater.nq.outputwriter;

import blater.nq.ParameterParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputTypeTest {
  @Test
  void commandLineCatalogDefaultsToMarkdownWhileOtherCommandsDefaultToJson() {
    NestScript catalogScript = new NestScript(List.of(NestStatement.catalog(null)));

    assertEquals(
        OutputType.MARKDOWN,
        OutputType.get(
            catalogScript,
            Map.of(ParameterParser.CATALOG_PATTERN_PARAM, "")));
    assertEquals(OutputType.JSON, OutputType.get(catalogScript, Map.of()));
  }

  @Test
  void explicitOutputSelectionsOverrideTheCatalogDefault() {
    NestScript yamlScript = new NestScript(OutputType.YAML, List.of());
    Map<String, String> catalog = Map.of(ParameterParser.CATALOG_PATTERN_PARAM, "");
    Map<String, String> commandLineJson = Map.of(
        ParameterParser.CATALOG_PATTERN_PARAM, "",
        ParameterParser.OUTPUT_TYPE_PARAM, "json");

    assertEquals(OutputType.YAML, OutputType.get(yamlScript, catalog));
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
