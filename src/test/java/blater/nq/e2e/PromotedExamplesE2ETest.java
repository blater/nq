package blater.nq.e2e;

import blater.nq.testsupport.CliTestHarness;
import blater.nq.testsupport.ParquetTestFiles;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromotedExamplesE2ETest {
  @TempDir
  Path tempDir;

  @Test
  void readmeFirstQueryMatchesDocumentedOutput() throws Exception {
    String output = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", "select id, name from customers where city = 'London' order by id;",
        "--input-file", "docs/examples/customers.json"));

    assertEquals("[{\"id\":\"1\",\"name\":\"Alice\"},{\"id\":\"3\",\"name\":\"Eva\"}]\n", output);
  }

  @Test
  void promotedNestedSummaryMatchesAcrossJsonYamlAndXml() throws Exception {
    String expected = "{\"result\":{\"region\":[{\"country\":\"GB\",\"customerCount\":\"2\"},{\"country\":\"US\",\"customerCount\":\"4\"}]}}\n";
    for (String extension : new String[]{"json", "yaml", "xml"}) {
      String output = captureStdout(() -> CliTestHarness.run(
          "run", "--script-file", "docs/examples/identity-country-counts.nq",
          "--input-file", "docs/examples/identity-customers." + extension));
      assertEquals(expected, output, extension);
    }
  }

  @Test
  void jqComparisonAndDatabaseHierarchyRecipesMatchExpectedOutput() throws Exception {
    String comparison = captureStdout(() -> CliTestHarness.run(
        "run", "--script-file", "docs/examples/jq/maximal.nq",
        "--input-file", "docs/examples/jq/elements.json"));
    assertEquals("[{\"id\":\"2\"},{\"id\":\"3\"}]\n", comparison);

    String database = "mem:recipe_" + UUID.randomUUID().toString().replace("-", "");
    String hierarchy = captureStdout(() -> CliTestHarness.run(
        "run", "--script-file", "docs/recipes/database-to-nested-json/database-to-nested-json.nq",
        "--db", "h2", "--database", database));
    assertEquals(
        Files.readString(Path.of("docs/recipes/database-to-nested-json/expected.json")).strip() + "\n",
        hierarchy);
  }

  @Test
  void jsonToDatabaseRecipePersistsAndVerifiesTheMappedRow() throws Exception {
    String database = "file:" + tempDir.resolve("json-to-database").toAbsolutePath();
    String input = captureStdout(() -> CliTestHarness.run(
        "run", "--script-file", "docs/recipes/json-to-database/json-to-database.nq",
        "--input-file", "docs/recipes/json-to-database/person.json",
        "--db", "h2", "--database", database));
    assertEquals("{\"message\":{\"person\":{\"firstName\":\"Fred\",\"city\":\"Bedrock\"}}}\n", input);

    String verification = captureStdout(() -> CliTestHarness.run(
        "run", "--script-text", "select id as person_key, id into {result.person.id}, "
            + "first_name into {result.person.firstName}, city into {result.person.city} "
            + "from person structure {result.person} key (person_key);",
        "--db", "h2", "--database", database));
    assertEquals(
        Files.readString(Path.of("docs/recipes/json-to-database/expected.json")).strip() + "\n",
        verification);
  }

  @Test
  void delimitedJsonLinesAndParquetFormatsHaveCliSmokeCoverage() throws Exception {
    Path csv = tempDir.resolve("customers.csv");
    Files.writeString(csv, "id,city\n1,London\n2,Paris\n");
    assertEquals(
        "[{\"id\":\"1\"}]\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", "select id from item where city = 'London';",
            "--input-file", csv.toString())));

    Path tsv = tempDir.resolve("customers.tsv");
    Files.writeString(tsv, "id\tcity\n1\tLondon\n2\tParis\n");
    assertEquals(
        "id\n2\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", "output tsv; select id from item where city = 'Paris';",
            "--input-file", tsv.toString())));

    Path toml = tempDir.resolve("customers.toml");
    Files.writeString(toml, """
        [[customers]]
        id = 1
        city = "London"

        [[customers]]
        id = 2
        city = "Paris"
        """);
    assertEquals(
        "[[item]]\n\n[item.result]\nid = \"1\"\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", "output toml; select id into {result.id} from customers where city = 'London';",
            "--input-file", toml.toString())));

    Path jsonl = tempDir.resolve("customers.jsonl");
    Files.writeString(jsonl, "{\"id\":1,\"city\":\"London\"}\n{\"id\":2,\"city\":\"Paris\"}\n");
    assertEquals(
        "[{\"id\":\"2\"}]\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", "select id from item where city = 'Paris';",
            "--input-file", jsonl.toString())));

    var schema = ParquetTestFiles.schema("""
        message customer {
          required int32 id;
          required binary city (UTF8);
        }
        """);
    SimpleGroupFactory groups = ParquetTestFiles.factory(schema);
    Path parquet = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(
        parquet,
        schema,
        groups.newGroup().append("id", 1).append("city", "London"),
        groups.newGroup().append("id", 2).append("city", "Paris"));
    assertEquals(
        "[{\"id\":\"1\"}]\n",
        captureStdout(() -> CliTestHarness.run(
            "run", "--script-text", "select id from customer where city = 'London';",
            "--input-file", parquet.toString())));
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
