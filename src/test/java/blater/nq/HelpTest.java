package blater.nq;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelpTest {
  @Test
  void noArgumentsPrintsShortHelp() throws Exception {
    assertFalse(captureStdout(Main::main).isBlank());
  }

  @Test
  void shortHelpIsBriefAndLongHelpPrintsTheManPage() throws Exception {
    String shortHelp = captureStdout(() -> Main.main("-h"));
    String longHelp = captureStdout(() -> Main.main("--help"));

    assertTrue(shortHelp.startsWith("Usage:\n"));
    assertTrue(shortHelp.contains("Connection options:"));
    assertTrue(shortHelp.contains("-p <properties-file>"));
    assertTrue(shortHelp.contains("--jdbc-database <url>"));
    assertTrue(shortHelp.contains("nq <input-file> [cache-options]"));
    assertTrue(shortHelp.contains("--use-cache <input-file-or-cache-filename>"));
    assertTrue(shortHelp.contains("--parquet-record <name>"));
    assertTrue(shortHelp.contains("--anonymous-collections <merge|error>"));
    assertTrue(shortHelp.contains("--relation-alias <source-path>=<relation-name>"));
    assertTrue(shortHelp.contains("Run 'nq --help' for the complete manual"));
    assertFalse(shortHelp.contains("NQ(1)"));
    assertTrue(longHelp.startsWith("NQ(1)"));
    assertTrue(longHelp.contains("\nSYNOPSIS\n"));
    assertTrue(longHelp.contains("--help <topic>"));
  }

  @Test
  void helpOnHelpListsAvailableTopics() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "help"));

    assertTrue(output.startsWith("HELP\n"));
    assertTrue(output.contains("AVAILABLE HELP TOPICS"));
    assertTrue(output.contains("nq --help query"));
    assertTrue(output.contains("clear-cache"));
    assertTrue(output.contains("catalog"));
  }

  @Test
  void commandHelpPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "query"));

    assertTrue(output.startsWith("QUERY\n"));
    assertTrue(output.contains("nq <script-file-or-text>"));
    assertTrue(output.contains("--relation-alias"));
    assertTrue(output.contains("nq --help connection"));
  }

  @Test
  void queryAndCacheHelpSeparateEphemeralQueriesFromPersistentCaches() throws Exception {
    String query = captureStdout(() -> Main.main("--help", "query"));
    String output = captureStdout(() -> Main.main("--help", "cache"));

    assertTrue(query.contains("temporary in-memory H2"));
    assertTrue(output.contains("persistent local H2"));
    assertTrue(output.contains("file-backed H2"));
    assertFalse(output.contains("temporary in-memory H2"));
  }

  @Test
  void useCacheHelpExplainsThatItOnlySelectsExistingCaches() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "use-cache"));

    assertTrue(output.startsWith("USE-CACHE\n"));
    assertTrue(output.contains("nq --use-cache <input-file-or-cache-filename>"));
    assertTrue(output.contains("does not create one"));
    assertTrue(output.contains("materialization options"));
  }

  @Test
  void equalsFormPrintsFocusedTopic() throws Exception {
    String output = captureStdout(() -> Main.main("--help=output"));

    assertTrue(output.startsWith("OUTPUT\n"));
    assertTrue(output.contains("--output <type>"));
  }

  @Test
  void catalogHelpDescribesPatternsAndConnectionSelection() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "catalog"));

    assertTrue(output.startsWith("CATALOG\n"));
    assertTrue(output.contains("nq catalog [table-pattern]"));
    assertTrue(output.contains("active cache"));
    assertTrue(output.contains("Quote patterns"));
  }

  @Test
  void helpShortCircuitsOtherCommandLineProcessing() throws Exception {
    String output = captureStdout(() -> Main.main(
        "-p", "/file/that/does/not/exist.properties", "--help", "cache"));

    assertTrue(output.startsWith("CACHE\n"));
  }

  @Test
  void unknownTopicPointsToTopicListing() throws Exception {
    String output = captureStdout(() -> Main.main("--help", "unknown"));

    assertTrue(output.startsWith("Unknown help topic: unknown"));
    assertTrue(output.contains("nq --help help"));
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
