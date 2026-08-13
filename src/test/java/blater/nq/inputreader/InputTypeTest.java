package blater.nq.inputreader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputTypeTest {
  @Test
  void determinesInputTypeFromFilenameExtension() {
    assertEquals(InputType.XML, InputType.fromFilename("input.xml"));
    assertEquals(InputType.JSON, InputType.fromFilename("input.json"));
    assertEquals(InputType.JSON, InputType.fromFilename("INPUT.JSON"));
    assertEquals(InputType.JSONL, InputType.fromFilename("input.jsonl"));
    assertEquals(InputType.JSONL, InputType.fromFilename("INPUT.JSONL"));
    assertEquals(InputType.YAML, InputType.fromFilename("input.yaml"));
    assertEquals(InputType.YAML, InputType.fromFilename("input.yml"));
    assertEquals(InputType.YAML, InputType.fromFilename("INPUT.YAML"));
    assertEquals(InputType.CSV, InputType.fromFilename("input.csv"));
    assertEquals(InputType.CSV, InputType.fromFilename("INPUT.CSV"));
    assertEquals(InputType.TSV, InputType.fromFilename("input.tsv"));
    assertEquals(InputType.TSV, InputType.fromFilename("INPUT.TSV"));
    assertEquals(InputType.TOML, InputType.fromFilename("input.toml"));
    assertEquals(InputType.TOML, InputType.fromFilename("INPUT.TOML"));
    assertEquals(InputType.PARQUET, InputType.fromFilename("input.parquet"));
    assertEquals(InputType.PARQUET, InputType.fromFilename("INPUT.PARQUET"));
  }

  @Test
  void selectsTabSeparatedInputByName() {
    assertEquals(InputType.TSV, InputType.fromName("tsv"));
    assertEquals(InputType.TSV, InputType.fromName("TSV"));
  }

  @Test
  void selectsTomlInputByName() {
    assertEquals(InputType.TOML, InputType.fromName("toml"));
    assertEquals(InputType.TOML, InputType.fromName("TOML"));
  }

  @Test
  void mapsEmptyFilenameToEmptyXmlHierarchy() {
    assertEquals(InputType.XML, InputType.fromFilename(""));
  }

  @Test
  void rejectsUnknownInputFileExtension() {
    assertThrows(IllegalArgumentException.class, () -> InputType.fromFilename("input.txt"));
  }

  @Test
  void reportsWhetherFilenameHasASupportedInputExtension() {
    assertTrue(InputType.supportsFilename("records.JSONL"));
    assertFalse(InputType.supportsFilename("records.txt"));
    assertFalse(InputType.supportsFilename(""));
    assertFalse(InputType.supportsFilename(null));
  }
}
