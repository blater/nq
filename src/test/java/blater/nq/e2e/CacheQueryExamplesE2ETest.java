package blater.nq.e2e;

import blater.nq.ParameterParser;
import blater.nq.outputwriter.JsonOutputWriter;
import blater.nq.parser.ScriptLoader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.runner.ScriptRunner;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.cache.CacheExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CacheQueryExamplesE2ETest {
  @TempDir
  Path tempDir;

  @Test
  void aggregatesIdentityCustomersByResidentialAddressCountry() throws Exception {
    NestScript script = ScriptParser.parse(ScriptLoader.load(
        Path.of("docs", "examples", "identity-country-counts.nq").toString()));

    for (String filename : List.of(
        "identity-customers.json",
        "identity-customers.yaml",
        "identity-customers.xml")) {

      Map<String, String> parameters = Map.of(
          ParameterParser.CACHE_MODE_PARAM, "true",
          ParameterParser.CACHE_DIR_PARAM, tempDir.resolve("cache-" + filename).toString(),
          ParameterParser.INPUT_FILENAME, Path.of("docs", "examples", filename).toString());
      SqlExecutor executor = CacheExecution.openForQuery(parameters).orElseThrow();
      blater.nq.domain.Hierarchy hierarchy;
      try {
        hierarchy = ScriptRunner.run(script, parameters, executor);
      } finally {
        executor.close();
      }

      assertEquals(
          "{\"result\":{\"region\":[{\"country\":\"GB\",\"customerCount\":\"2\"},{\"country\":\"US\",\"customerCount\":\"4\"}]}}",
          JsonOutputWriter.map(hierarchy),
          filename);
    }
  }
}
