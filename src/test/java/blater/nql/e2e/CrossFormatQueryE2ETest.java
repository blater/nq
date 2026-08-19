package blater.nql.e2e;

import blater.nql.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static blater.nql.testsupport.CliTestHarness.captureStdout;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossFormatQueryE2ETest {
    private static final String[] INPUT_FORMATS = {"json", "yaml", "csv", "tsv"};

    @Test
    void equivalentQuerySelectsAndOrdersEscapedUnicodeRowsAcrossAllInputFormats() throws Exception {
        String script = """
                select cast(id as integer) as id, name, city
                from item
                where cast(active as boolean) = true
                order by cast(id as integer);
                """;
        String expected = """
                [{"id":1,"name":"Ada, \\"Ace\\"","city":"Zürich"},{"id":3,"name":"水 😀","city":"東京"}]
                """;

        for (String format : INPUT_FORMATS) {
            assertEquals(expected, query(format, script), "input format: " + format);
        }
    }

    @Test
    void jsonAndYamlQueriesTreatNullAndMissingFieldsConsistently() throws Exception {
        String script = """
                select cast(id as integer) as id, coalesce(note, '<none>') as note
                from item
                order by cast(id as integer);
                """;
        String expected = """
                [{"id":1,"note":"<none>"},{"id":2,"note":"line one\\nline two"},{"id":3,"note":"<none>"}]
                """;

        assertEquals(expected, query("json", script));
        assertEquals(expected, query("yaml", script));
    }

    @Test
    void queryResultsRenderAsJsonYamlCsvAndTsv() throws Exception {
        String script = """
                select cast(id as integer) as id, name
                from item
                where cast(active as boolean) = true
                order by cast(id as integer);
                """;

        assertEquals("""
                [{"id":1,"name":"Ada, \\"Ace\\""},{"id":3,"name":"水 😀"}]
                """, query("json", script, "json"));
        assertEquals("""
                -
                  id: "1"
                  name: "Ada, \\"Ace\\""
                -
                  id: "3"
                  name: "水 😀"

                """, query("json", script, "yaml"));
        assertEquals("""
                id,name
                1,\"Ada, \"\"Ace\"\"\"
                3,水 😀
                """, query("json", script, "csv"));
        assertEquals("""
                id	name
                1	\"Ada, \"\"Ace\"\"\"
                3	水 😀
                """, query("json", script, "tsv"));
    }

    private String query(String inputFormat, String script) throws Exception {
        return query(inputFormat, script, "json");
    }

    private String query(String inputFormat, String script, String outputFormat) throws Exception {
        String[] args = {
                "run",
                "--script-text", script,
                "--input-file", fixture(inputFormat).toString(),
                "--input-format", inputFormat,
                "--output", outputFormat
        };
        return captureStdout(() -> CliTestHarness.run(args));
    }

    private Path fixture(String format) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                getClass().getResource("/query-formats/records." + format)).toURI());
    }

}
