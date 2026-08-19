package blater.nql.cli.parse;

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
    assertUsageFailure("report.nql", "-dt");
  }

  @Test
  void rejectsDuplicateScalarOptions() {
    assertUsageFailure(
        "convert", "customers.json", "--output", "json", "--output", "yaml");
  }

  @Test
  void rejectsPositionalAndNamedScriptConflict() {
    assertUsageFailure("report.nql", "--script-file", "other.nql");
  }

  @Test
  void rejectsScriptFileAndScriptTextConflict() {
    assertUsageFailure(
        "--script-file", "report.nql", "--script-text", "select id from customer;");
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
    assertUsageFailure("report.nql", "--name", "customers");
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
    assertUsageFailure("report.nql", "customers.json", "--cache-dir", "cache");
    assertUsageFailure(
        "report.nql", "--jdbc-database", "jdbc:h2:mem:test", "--cache-dir", "cache");
    assertUsageFailure("convert", "customers.json", "--cache-dir", "cache");
  }

  @Test
  void rejectsParquetModifiersForNonParquetInput() {
    assertUsageFailure("convert", "customers.json", "--parquet-root", "rows");
    assertUsageFailure("report.nql", "--input-format", "json", "--parquet-record", "row");
  }

  private void assertUsageFailure(String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}
