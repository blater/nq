package blater.nq;

import blater.nq.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCliTest {
  @TempDir
  Path tempDir;

  @Test
  void cacheDirectoryContainsCachesDirectlyAndNoImplicitConfiguration() throws Exception {
    Path input = Files.writeString(tempDir.resolve("input.json"), "{\"items\":[{\"id\":1}]}");
    Path cache = tempDir.resolve("cache");

    String report = captureStdout(() -> CliTestHarness.run(
        "cache", "load", input.toString(),
        "--cache-dir", cache.toString(),
        "--report-format", "json"));

    assertTrue(report.contains("\"command\":\"cache.load\""));
    try (var files = Files.list(cache)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
    }
    assertFalse(Files.exists(tempDir.resolve("config.properties")));
  }

  @Test
  void catalogInputIsEphemeralAndUsesReportFormat() throws Exception {
    Path input = Files.writeString(tempDir.resolve("input.json"), "{\"items\":[{\"id\":1}]}");
    String report = captureStdout(() -> CliTestHarness.run(
        "catalog", input.toString(), "*",
        "--report-format", "json"));

    assertTrue(report.contains("\"command\":\"catalog\""));
    assertTrue(report.contains("\"details\":{\"catalog\":"));
    assertTrue(report.contains("\"ITEMS\""));
    try (var files = Files.list(tempDir)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".mv.db")));
    }
  }

  @Test
  void administrativeReportsSupportYamlAndMarkdown() throws Exception {
    Path cache = tempDir.resolve("reports");
    String yaml = captureStdout(() -> CliTestHarness.run(
        "cache", "list", "--cache-dir", cache.toString(), "--report-format", "yaml"));
    String markdown = captureStdout(() -> CliTestHarness.run(
        "cache", "list", "--cache-dir", cache.toString(), "--report-format", "markdown"));

    assertTrue(yaml.startsWith("schema_version:"));
    assertTrue(yaml.contains("command: \"cache.list\""));
    assertTrue(markdown.startsWith("|"));
    assertTrue(markdown.contains("cache.list"));
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
