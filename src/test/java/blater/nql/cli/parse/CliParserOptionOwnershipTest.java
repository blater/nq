package blater.nql.cli.parse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserOptionOwnershipTest {
  private final CliParser parser = new CliParser();

  @Test
  void rejectsDuplicateBooleanFlagsIncludingAliases() {
    assertUsage("report.nql", "--cache", "--cache");
    assertUsage("report.nql", "--debug", "--debug");
    assertUsage("report.nql", "--no-key-inference", "--no-key-inference");
    assertUsage("cache", "clear", "--all", "--all");
    assertUsage("--help", "--help");
    assertUsage("-h", "-h");
    assertUsage("--version", "--version");
    assertUsage("-h", "--help");
  }

  @Test
  void rejectsEveryLongEqualsAndAttachedShortShapeBeforeSemanticBinding() {
    assertUsage("convert", "data.json", "--output=json");
    assertUsage("report.nql", "--cache=true");
    assertUsage("report.nql", "--param=x=1");
    assertUsage("convert", "data.json", "-ojson");
    assertUsage("report.nql", "-fscript.nql");
    assertUsage("report.nql", "-ho");
  }

  @Test
  void conversionRejectsCacheClearOptions() {
    assertUsage("convert", "data.json", "--all");
    assertUsage("convert", "data.json", "--older-than", "7d");
  }

  @Test
  void catalogRejectsRunAndCacheClearOnlyOptions() {
    assertUsage("catalog", "--no-key-inference");
    assertUsage("catalog", "--all");
    assertUsage("catalog", "--older-than", "7d");
  }

  @Test
  void everyCacheSubcommandRejectsCatalogAndRunOnlyOptions() {
    assertUsage("cache", "load", "data.json", "--pattern", "customer*");
    assertUsage("cache", "load", "data.json", "--no-key-inference");
    assertUsage("cache", "use", "customers", "--pattern", "customer*");
    assertUsage("cache", "use", "customers", "--no-key-inference");
    assertUsage("cache", "list", "--pattern", "customer*");
    assertUsage("cache", "list", "--no-key-inference");
    assertUsage("cache", "clear", "customers", "--pattern", "customer*");
    assertUsage("cache", "clear", "customers", "--no-key-inference");
  }

  @Test
  void implicitCacheLoadRejectsOptionsOwnedByOtherCommands() {
    assertUsage("data.json", "--cache", "--pattern", "customer*");
    assertUsage("data.json", "--cache", "--no-key-inference");
    assertUsage("data.json", "--cache", "--all");
    assertUsage("data.json", "--cache", "--jdbc-database", "jdbc:h2:mem:test");
  }

  @Test
  void versionRejectsEveryNonVersionOptionFamily() {
    assertUsage("version", "--debug");
    assertUsage("version", "--input-format", "json");
    assertUsage("version", "--param", "region=eu");
    assertUsage("version", "--all");
  }

  private void assertUsage(String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}
