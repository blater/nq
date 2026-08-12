package blater.nq.e2e;

import blater.nq.Main;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliJdbcConnectionE2ETest {
  @Test
  void runsQueryWithSimpleH2ConnectionOptions() throws Exception {
    String output = captureStdout(() -> Main.main(
        "run", "--script-text", "output json; select 1 into {result.value};",
        "--db", "h2",
        "--database", "mem:" + databaseName()));

    assertEquals("""
        {"result":{"value":"1"}}
        """, output);
  }

  @Test
  void runsQueryWithExactJdbcConnectionOptions() throws Exception {
    String output = captureStdout(() -> Main.main(
        "run", "--script-text", "output json; select 1 into {result.value};",
        "--jdbc-driver", "h2",
        "--jdbc-database", "jdbc:h2:mem:" + databaseName()));

    assertEquals("""
        {"result":{"value":"1"}}
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
