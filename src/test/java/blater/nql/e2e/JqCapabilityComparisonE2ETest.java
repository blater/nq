package blater.nql.e2e;

import blater.nql.testsupport.CliTestHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static blater.nql.testsupport.CliTestHarness.captureStdout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JqCapabilityComparisonE2ETest {
  private static final String EXAMPLES = "docs/examples/jq/";

  @Test
  void groupingAndAggregationReplaceGroupByMapLengthAndAddPipeline() throws Exception {
    assertEquals(
        """
        [{"region":"north","sale_count":3,"revenue":316},{"region":"south","sale_count":2,"revenue":250}]
        """,
        run("regional-summary.nql", "sales.json"));
  }

  @Test
  void windowRankingReplacesNestedSortToEntriesAndFlattenPipeline() throws Exception {
    assertEquals(
        """
        [{"region":"north","rep":"Ada","amount":120.5,"sales_rank":1},{"region":"north","rep":"Alan","amount":120.5,"sales_rank":2},{"region":"north","rep":"Grace","amount":75,"sales_rank":3},{"region":"south","rep":"Linus","amount":200,"sales_rank":1},{"region":"south","rep":"Margaret","amount":50,"sales_rank":2}]
        """,
        run("ranked-sales.nql", "sales.json"));
  }

  @Test
  void joiningSiblingCollectionsReplacesCapturedArraySearches() throws Exception {
    assertEquals(
        """
        [{"customer":"Ada","order_count":2,"total":20},{"customer":"Grace","order_count":1,"total":30},{"customer":"Alan","order_count":0,"total":0}]
        """,
        run("customer-totals.nql", "customer-orders.json"));
  }

  @Test
  void decimalAggregatesUsePlainNotationAcrossOutputFormats() throws Exception {
    for (String format : List.of("json", "yaml", "csv", "tsv")) {
      String output = captureStdout(() -> CliTestHarness.run(
          "run",
          "--script-file", EXAMPLES + "regional-summary.nql",
          "--input-file", EXAMPLES + "sales.json",
          "--output", format));

      assertTrue(output.contains("250"), format);
      assertFalse(output.contains("E+") || output.contains("E-")
          || output.contains("e+") || output.contains("e-"), format);
    }
  }

  private String run(String script, String input) throws Exception {
    return captureStdout(() -> CliTestHarness.run(
        "run",
        "--script-file", EXAMPLES + script,
        "--input-file", EXAMPLES + input));
  }

}
