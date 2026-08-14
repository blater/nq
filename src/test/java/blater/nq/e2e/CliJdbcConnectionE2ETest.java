package blater.nq.e2e;

import blater.nq.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliJdbcConnectionE2ETest {
  @TempDir Path temporaryDirectory;

  @Test
  void runsQueryWithSimpleH2ConnectionOptions() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", "output json; select 1 into {result.value};",
        "--db", "h2",
        "--database", "mem:" + databaseName()));

    assertEquals("""
        {"result":{"value":1}}
        """, output);
  }

  @Test
  void runsQueryWithExactJdbcConnectionOptions() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", "output json; select 1 into {result.value};",
        "--jdbc-driver", "h2",
        "--jdbc-database", "jdbc:h2:mem:" + databaseName()));

    assertEquals("""
        {"result":{"value":1}}
        """, output);
  }

  @Test
  void exactJdbcUrlDoesNotRequireADuplicateDriverHint() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", "output json; select 1 into {result.value};",
        "--jdbc-database", "jdbc:h2:mem:" + databaseName()));

    assertEquals("""
        {"result":{"value":1}}
        """, output);
  }

  @Test
  void mappedDmlUsesExplicitInputFormatInsteadOfFilenameExtension() throws Exception {
    Path input = Files.writeString(
        temporaryDirectory.resolve("customer.payload"),
        "{\"customer\":{\"id\":7}}",
        StandardCharsets.UTF_8);
    String output = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", """
            output json;
            literal create table customer_result (id varchar(20));
            insert into customer_result (id) values ({customer.id});
            select id into {result.id} from customer_result;
            """,
        "--input-file", input.toString(), "--input-format", "json",
        "--jdbc-database", "jdbc:h2:mem:" + databaseName()));

    assertEquals("""
        {"customer":{"id":7}}
        """, output);
  }

  private String databaseName() {
    return "cli_" + UUID.randomUUID().toString().replace("-", "");
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
