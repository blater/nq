package blater.nq;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {
  @Test
  void versionHasStableCliPrefix() throws Exception {
    String output = captureStdout(() -> Main.main("--version"));

    assertTrue(output.startsWith("nq "));
    assertTrue(output.endsWith(System.lineSeparator()));
  }

  @Test
  void noArgumentsPrintsShortHelp() throws Exception {
    assertFalse(captureStdout(Main::main).isBlank());
  }

  @Test
  void shortHelpIsBriefAndLongHelpPrintsTheManPage() throws Exception {
    String shortHelp = captureStdout(() -> Main.main("-h"));
    String longHelp = captureStdout(() -> Main.main("--help"));

    assertTrue(shortHelp.startsWith("Usage:\n"));
    assertTrue(shortHelp.contains("Run and connection options:"));
    assertTrue(shortHelp.contains("--properties <properties-file>"));
    assertTrue(shortHelp.contains("--jdbc-database <url>"));
    assertTrue(shortHelp.contains("nq convert --input-file"));
    assertTrue(shortHelp.contains("nq cache load"));
    assertTrue(shortHelp.contains("nq cache use --name"));
    assertTrue(shortHelp.contains("--input-format <xml|json|jsonl|yaml|toml|csv|tsv|parquet>"));
    assertTrue(shortHelp.contains("--input-format"));
    assertTrue(shortHelp.contains("Run 'nq help' for topics"));
    assertFalse(shortHelp.contains("NQ(1)"));
    assertTrue(longHelp.startsWith("NQ(1)"));
    assertTrue(longHelp.contains("\nSYNOPSIS\n"));
    assertTrue(longHelp.contains("--help [topic]"));
  }

  @Test
  void helpOnHelpListsAvailableTopics() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "help"));

    assertTrue(output.startsWith("HELP\n"));
    assertTrue(output.contains("TOPICS"));
    assertTrue(output.contains("run"));
    assertTrue(output.contains("convert"));
    assertTrue(output.contains("catalog"));
  }

  @Test
  void commandHelpPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "query"));

    assertTrue(output.startsWith("RUN\n"));
    assertTrue(output.contains("nq run --script-file"));
    assertTrue(output.contains("--script-text"));
  }

  @Test
  void queryAndCacheHelpSeparateEphemeralQueriesFromPersistentCaches() throws Exception {
    String query = captureStdout(() -> Main.main("--help", "query"));
    String output = captureStdout(() -> Main.main("--help", "cache"));

    assertTrue(query.contains("temporary H2"));
    assertTrue(output.contains("persistent local H2"));
    assertTrue(output.contains("--state-dir"));
  }

  @Test
  void useCacheHelpExplainsThatItOnlySelectsExistingCaches() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "cache"));

    assertTrue(output.startsWith("CACHE\n"));
    assertTrue(output.contains("nq cache use --name"));
    assertTrue(output.contains("--state-dir"));
  }

  @Test
  void equalsFormPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> Main.main("--help=output"));

    assertTrue(output.startsWith("OUTPUT\n"));
    assertTrue(output.contains("--report-format"));
  }

  @Test
  void catalogHelpDescribesPatternsAndConnectionSelection() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "catalog"));

    assertTrue(output.startsWith("CATALOG\n"));
    assertTrue(output.contains("nq catalog [--pattern"));
    assertTrue(output.contains("active cache"));
    assertTrue(output.contains("ephemeral"));
  }

  @Test
  void helpShortCircuitsOtherCommandLineProcessing() throws Exception {
    String output = captureStdout(() -> Main.main(
        "--properties", "/file/that/does/not/exist.properties", "--help", "cache"));

    assertTrue(output.startsWith("CACHE\n"));
  }

  @Test
  void unknownTopicPointsToTopicListing() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "unknown"));

    assertTrue(output.startsWith("Unknown help topic: unknown"));
    assertTrue(output.contains("nq help"));
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
