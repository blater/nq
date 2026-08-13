package blater.nq.cli.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserSyntaxValidationTest {
  private final CliParser parser = new CliParser();

  @Test
  void rejectsLongOptionEqualsSyntax() {
    assertUsageFailure("convert", "customers.json", "--output=yaml");
  }

  @Test
  void rejectsAttachedShortOptionValues() {
    assertUsageFailure("convert", "customers.json", "-oyaml");
  }

  @Test
  void rejectsShortOptionBundles() {
    assertUsageFailure("report.nq", "-dt");
  }

  @Test
  void rejectsDuplicateScalarOptions() {
    assertUsageFailure(
        "convert", "customers.json", "--output", "json", "--output", "yaml");
  }

  @Test
  void rejectsPositionalAndNamedScriptConflict() {
    assertUsageFailure("report.nq", "--script-file", "other.nq");
  }

  @Test
  void rejectsScriptFileAndScriptTextConflict() {
    assertUsageFailure(
        "--script-file", "report.nq", "--script-text", "select id from customer;");
  }

  @Test
  void rejectsPositionalAndNamedInputConflict() {
    assertUsageFailure("convert", "customers.json", "--input-file", "other.json");
  }

  @Test
  void rejectsInputFileAndInputTextConflict() {
    assertUsageFailure(
        "convert", "--input-file", "customers.json", "--input-text", "{\"id\":1}");
  }

  @Test
  void rejectsPositionalAndNamedCacheNameConflict() {
    assertUsageFailure("cache", "use", "customers", "--name", "other");
  }

  @Test
  void rejectsNamedCacheWithoutCacheSelectionForRun() {
    assertUsageFailure("report.nq", "--name", "customers");
  }

  @Test
  void rejectsDuplicateCacheClearTargetKinds() {
    assertUsageFailure("cache", "clear", "customers", "--all");
    assertUsageFailure("cache", "clear", "all", "--older-than", "7d");
  }

  @Test
  void rejectsUnexpectedExtraConversionOperands() {
    assertUsageFailure("convert", "first.json", "second.json");
  }

  @Test
  void rejectsOptionShapedTokensAfterDelimiterAsUnexpectedOperands() {
    assertUsageFailure("convert", "customers.json", "--", "--output", "yaml");
  }

  @Test
  void rejectsExplicitCacheDirectoryForNonCacheExecutionTargets() {
    assertUsageFailure("report.nq", "customers.json", "--cache-dir", "cache");
    assertUsageFailure(
        "report.nq", "--jdbc-database", "jdbc:h2:mem:test", "--cache-dir", "cache");
    assertUsageFailure("convert", "customers.json", "--cache-dir", "cache");
  }

  @Test
  void rejectsParquetModifiersForNonParquetInput() {
    assertUsageFailure("convert", "customers.json", "--parquet-root", "rows");
    assertUsageFailure("report.nq", "--input-format", "json", "--parquet-record", "row");
  }

  private void assertUsageFailure(String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}
