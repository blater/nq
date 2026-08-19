package blater.nql.e2e;

import blater.nql.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static blater.nql.testsupport.CliTestHarness.captureStdout;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DifficultJsonQueryE2ETest {
  @TempDir
  Path tempDir;

  @Test
  void queriesRaggedRecordsWithMissingAndEmptyNestedObjects() throws Exception {
    Path input = write("ragged.json", """
        {
          "record": [
            {"id": 1, "name": "bare"},
            {"id": 2, "name": "scored", "profile": {"score": 9, "flags": []}},
            {"id": 3, "name": "empty", "profile": {}},
            {"id": 4, "name": "zero", "profile": {"score": 0, "address": {}}}
          ]
        }
        """);

    assertEquals(
        """
        [{"id":1,"name":"bare","score":null},{"id":2,"name":"scored","score":9},{"id":3,"name":"empty","score":null},{"id":4,"name":"zero","score":0}]
        """,
        query(input, """
            select r.id, r.name, p.score
            from record r
            left join profile p on p.record_id = r.id
            order by r.id;
            """));
  }

  @Test
  void queriesNestedObjectArraysRepeatedScalarsAndEmptyArrays() throws Exception {
    Path input = write("nested-collections.json", """
        {
          "batch": [
            {
              "id": "B1",
              "item": [
                {"id": "I1", "tag": ["red", "large"]},
                {"id": "I2", "tag": []}
              ]
            },
            {"id": "B2", "item": []},
            {"id": "B3", "item": [{"id": "I3", "tag": ["fragile"]}]}
          ]
        }
        """);

    assertEquals(
        """
        [{"batch_id":"B1","item_id":"I1","tag":"red"},{"batch_id":"B1","item_id":"I1","tag":"large"},{"batch_id":"B1","item_id":"I2","tag":null},{"batch_id":"B2","item_id":null,"tag":null},{"batch_id":"B3","item_id":"I3","tag":"fragile"}]
        """,
        query(input, """
            select b.id as batch_id, i.id as item_id, t.value as tag
            from batch b
            left join item i on i.batch_id = b.id
            left join item_tag t on t.item_id = i.id
            order by b.id, i.id, t._nql_id;
            """));
  }

  private Path write(String filename, String json) throws Exception {
    return Files.writeString(tempDir.resolve(filename), json, StandardCharsets.UTF_8);
  }

  private String query(Path input, String script) {
    return captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", script, "--input-file", input.toString()));
  }
}
