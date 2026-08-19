package blater.nql.e2e;

import blater.nql.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static blater.nql.testsupport.CliTestHarness.captureStdout;
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

}
