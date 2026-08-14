package blater.nq.e2e;

import blater.nq.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTypeAndIdentifierE2ETest {
  @TempDir
  Path tempDir;

  @Test
  void queriesTypedJsonWhenNestedObjectUsesReservedTableName() throws Exception {
    Path input = tempDir.resolve("order.json");
    Files.writeString(input, """
        {
          "customer": {
            "id": "xyz",
            "ident": {
              "user": {
                "isExternal": false,
                "profile": {
                  "firstname": "John",
                  "height": 180,
                  "lastname": "Appleseed"
                }
              }
            }
          },
          "line_items": {
            "items": [
              {"is_gratis":false,"name":"pizza","price":4.8},
              {"is_gratis":false,"name":"salami","price":2.8},
              {"is_gratis":false,"name":"cheese","price":2},
              {"is_gratis":true,"name":"chilli","price":0}
            ]
          }
        }
        """, StandardCharsets.UTF_8);

    String script = """
        select
          concat(p.firstname, ' ', p.lastname) as "fullname",
          (
            select sum(i.price)
            from items i
            where i.is_gratis = false
          ) as "total"
        from profile p;
        """;

    assertEquals(
        "[{\"fullname\":\"John Appleseed\",\"total\":9.6}]\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", script, "--input-file", input.toString())));
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
