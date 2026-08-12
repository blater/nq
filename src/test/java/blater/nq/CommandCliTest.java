package blater.nq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCliTest {
  @TempDir
  Path tempDir;

  @Test
  void explicitCommandsAndOperandsAreRequired() {
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("query.nq"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("run", "query.nq"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "run", "--script-file", "query.nq", "--script-text", "select 1;"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("convert"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("cache"));
  }

  @Test
  void runNamesEveryOperandAndParameter() {
    Map<String, String> params = ParameterParser.parse(
        "run",
        "--script-file", "query.nq",
        "--input-file", "input.json",
        "--param", "region=EMEA",
        "--output", "yaml");

    assertEquals("run", params.get(ParameterParser.COMMAND_PARAM));
    assertEquals("query.nq", params.get(ParameterParser.SCRIPT_FILE_PARAM));
    assertEquals("input.json", params.get(ParameterParser.INPUT_FILENAME));
    assertEquals("EMEA", params.get("region"));
    assertEquals("yaml", params.get(ParameterParser.OUTPUT_TYPE_PARAM));
  }

  @Test
  void stateDirectoryContainsConfigurationAndCaches() throws Exception {
    Path input = Files.writeString(tempDir.resolve("input.json"), "{\"items\":[{\"id\":1}]}");
    Path state = tempDir.resolve("state");

    String report = captureStdout(() -> Main.main(
        "cache", "load",
        "--input-file", input.toString(),
        "--state-dir", state.toString(),
        "--report-format", "json"));

    assertTrue(report.contains("\"command\":\"cache load\""));
    assertTrue(Files.exists(state.resolve("config.properties")));
    try (var files = Files.list(state.resolve("cache"))) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
    }
    assertFalse(Files.exists(tempDir.resolve("config.properties")));
  }

  @Test
  void catalogInputIsEphemeralAndUsesReportFormat() throws Exception {
    Path input = Files.writeString(tempDir.resolve("input.json"), "{\"items\":[{\"id\":1}]}");
    Path state = tempDir.resolve("catalog-state");

    String report = captureStdout(() -> Main.main(
        "catalog", "--input-file", input.toString(), "--pattern", "*",
        "--state-dir", state.toString(), "--report-format", "json"));

    assertTrue(report.startsWith("{\"catalog\""));
    assertTrue(report.contains("\"ITEMS\""));
    assertFalse(Files.exists(state));
  }

  @Test
  void administrativeReportsSupportYamlAndMarkdown() throws Exception {
    Path state = tempDir.resolve("reports");
    String yaml = captureStdout(() -> Main.main(
        "cache", "list", "--state-dir", state.toString(), "--report-format", "yaml"));
    String markdown = captureStdout(() -> Main.main(
        "cache", "list", "--state-dir", state.toString(), "--report-format", "markdown"));

    assertTrue(yaml.startsWith("report:\n"));
    assertTrue(yaml.contains("command: \"cache list\""));
    assertTrue(markdown.startsWith("|"));
    assertTrue(markdown.contains("cache list"));
  }

  @Test
  void cacheClearRequiresAnExplicitScope() {
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("cache", "clear"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "cache", "clear", "--all", "--older-than", "1d"));
  }

  @Test
  void unsupportedFormatsFailInsteadOfSilentlyDefaulting() {
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "convert", "--input-file", "input.json", "--output", "jsno"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "cache", "list", "--report-format", "jsno"));
  }

  private String captureStdout(ThrowingRunnable runnable) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      runnable.run();
    } finally {
      System.setOut(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
