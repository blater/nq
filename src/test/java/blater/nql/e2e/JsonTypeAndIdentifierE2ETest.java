package blater.nql.e2e;

import blater.nql.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static blater.nql.testsupport.CliTestHarness.captureStdout;
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

}
