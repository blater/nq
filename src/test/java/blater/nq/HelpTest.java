package blater.nq;

import blater.nq.cli.parse.CliParser;
import blater.nq.cli.parse.CliUsageException;
import blater.nq.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {
  @Test
  void versionHasStableCliPrefix() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("--version"));

    assertTrue(output.startsWith("nq "));
    assertTrue(output.endsWith(System.lineSeparator()));
  }

  @Test
  void noArgumentsPrintBriefHelpEvenWhenStandardInputHasData() throws Exception {
    String output = captureStdin("{\"customer\":{\"id\":7}}", CliTestHarness::run);

    assertTrue(output.startsWith("Usage:\n"));
    assertFalse(output.contains("\"id\":\"7\""));
  }

  @Test
  void shortHelpIsBriefAndLongHelpPrintsTheManPage() throws Exception {
    String shortHelp = captureStdout(() -> CliTestHarness.run("-h"));
    String longHelp = captureStdout(() -> CliTestHarness.run("--help"));

    assertTrue(shortHelp.startsWith("Usage:\n"));
    assertTrue(shortHelp.contains("Common source options:"));
    assertTrue(shortHelp.contains("--config <file.properties>"));
    assertTrue(shortHelp.contains("nq convert [<data>]"));
    assertTrue(shortHelp.contains("nq cache load"));
    assertTrue(shortHelp.contains("nq cache use <name>"));
    assertTrue(shortHelp.contains("-t, --input-format <format>"));
    assertTrue(shortHelp.contains("--input-format"));
    assertTrue(shortHelp.contains("Run 'nq help' for commands"));
    assertFalse(shortHelp.contains("NQ(1)"));
    assertTrue(longHelp.startsWith("NQ(1)"));
    assertTrue(longHelp.contains("\nSYNOPSIS\n"));
    assertTrue(longHelp.contains("nq help [<command> [<subcommand>]]"));
  }

  @Test
  void helpOnHelpListsAvailableTopics() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("help", "help"));

    assertTrue(output.startsWith("HELP\n"));
    assertTrue(output.contains("TOPICS"));
    assertTrue(output.contains("run"));
    assertTrue(output.contains("convert"));
    assertTrue(output.contains("catalog"));
  }

  @Test
  void commandHelpPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("run", "--help"));

    assertTrue(output.startsWith("RUN\n"));
    assertTrue(output.contains("nq <script.nq> [<data>]"));
    assertTrue(output.contains("nq '<literal script>' ['<literal json>']"));
  }

  @Test
  void queryAndCacheHelpSeparateEphemeralQueriesFromPersistentCaches() throws Exception {
    String query = captureStdout(() -> CliTestHarness.run("help", "query"));
    String output = captureStdout(() -> CliTestHarness.run("cache", "--help"));

    assertTrue(query.contains("temporary"));
    assertTrue(query.contains("database"));
    assertTrue(output.contains("persistent local caches"));
    assertTrue(output.contains("--cache-dir"));
  }

  @Test
  void useCacheHelpExplainsThatItOnlySelectsExistingCaches() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("cache", "--help"));

    assertTrue(output.startsWith("CACHE\n"));
    assertTrue(output.contains("nq cache use <name>"));
    assertTrue(output.contains("--cache-dir"));
  }

  @Test
  void spacedHelpTopicPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("help", "output"));

    assertTrue(output.startsWith("OUTPUT\n"));
    assertTrue(output.contains("--report-format"));
  }

  @Test
  void catalogHelpDescribesPatternsAndConnectionSelection() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run("catalog", "--help"));

    assertTrue(output.startsWith("CATALOG\n"));
    assertTrue(output.contains("nq catalog [<data>] [<pattern>]"));
    assertTrue(output.contains("active"));
    assertTrue(output.contains("cache"));
    assertTrue(output.contains("temporary"));
    assertTrue(output.contains("database"));
  }

  @Test
  void helpShortCircuitsOtherCommandLineProcessing() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run(
        "cache", "--config", "/file/that/does/not/exist.properties", "--help"));

    assertTrue(output.startsWith("CACHE\n"));
  }

  @Test
  void unknownTopicIsAUsageError() {
    var failure = org.junit.jupiter.api.Assertions.assertThrows(
        CliUsageException.class,
        () -> new CliParser().parse("help", "unknown"));

    assertTrue(failure.getMessage().contains("Unknown help topic: unknown"));
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

  private String captureStdin(String input, ThrowingRunnable runnable) throws Exception {
    InputStream original = System.in;
    try {
      System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
      return captureStdout(runnable);
    } finally {
      System.setIn(original);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
