package blater.nql.cli.parse;

import blater.nql.cli.HelpInvocation;
import blater.nql.cli.VersionInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliParserHelpContractTest {
  @TempDir Path temporaryDirectory;

  @Test
  void helpTopicsAndCommandLocalHelpAreCaseInsensitive() {
    var parser = parser();

    assertHelp(parser.parse("help"), List.of(), false);
    assertHelp(parser.parse("help", "RUN"), List.of("run"), false);
    assertHelp(parser.parse("HeLp", "CaChE", "ClEaR"), List.of("cache", "clear"), false);
    assertHelp(parser.parse("RUN", "--help"), List.of("run"), false);
    assertHelp(parser.parse("CaChE", "ClEaR", "--help"), List.of("cache", "clear"), false);
    assertHelp(parser.parse("cache", "--help"), List.of("cache"), false);
  }

  @Test
  void rootHelpFlagsHaveTheirDocumentedBriefness() {
    var parser = parser();

    assertHelp(parser.parse("-h"), List.of(), true);
    assertHelp(parser.parse("--help"), List.of(), false);
  }

  @Test
  void helpDoesNotOpenScriptDataConfigOrParameterFiles() {
    var parser = parser();
    String missingScript = temporaryDirectory.resolve("missing.nql").toString();
    String missingData = temporaryDirectory.resolve("missing.json").toString();
    String missingConfig = temporaryDirectory.resolve("missing.properties").toString();
    String missingParameters = temporaryDirectory.resolve("missing-params.properties").toString();

    assertHelp(parser.parse(missingScript, missingData, "--help"), List.of("run"), false);
    assertHelp(
        parser.parse("run", "--help", "--config", missingConfig), List.of("run"), false);
    assertHelp(
        parser.parse("run", "--help", "--params-file", missingParameters),
        List.of("run"), false);
    assertHelp(
        parser.parse("convert", "--help", "--input-file", missingData),
        List.of("convert"), false);
  }

  @Test
  void versionDoesNotReadConfigurationAndRejectsItAsUnrelated() {
    var parser = parser();
    String missingConfig = temporaryDirectory.resolve("missing.properties").toString();

    assertInstanceOf(VersionInvocation.class, parser.parse("version"));
    assertInstanceOf(VersionInvocation.class, parser.parse("--version"));
    assertNoFileReadFailure(parser, "--version", "--config", missingConfig);
    assertNoFileReadFailure(parser, "version", "--params-file", missingConfig);
  }

  @Test
  void helpValidatesOwnedEnumLikeOptionValues() {
    var parser = parser();

    assertMessageContains(parser, "Unsupported output format", "convert", "--help", "--output", "bogus");
    assertMessageContains(
        parser, "Unsupported input format", "run", "--help", "--input-format", "bogus");
    assertMessageContains(
        parser, "Unsupported report format", "catalog", "--help", "--report-format", "bogus");
    assertMessageContains(
        parser, "Unsupported JDBC driver", "run", "--help", "--db", "bogus",
        "--database", "customers");
  }

  @Test
  void helpEnforcesCommandSpecificOptionOwnership() {
    var parser = parser();

    assertUsage(parser, "convert", "--help", "--report-format", "json");
    assertUsage(parser, "catalog", "--help", "--output", "json");
    assertUsage(parser, "cache", "use", "--help", "--input-format", "json");
    assertUsage(parser, "run", "--help", "--older-than", "7d");
    assertUsage(parser, "help", "run", "--debug");
  }

  @Test
  void unknownAndInvalidHelpTopicsAreUsageErrors() {
    var parser = parser();

    assertUsage(parser, "help", "wombat");
    assertUsage(parser, "help", "run", "clear");
    assertUsage(parser, "help", "cache", "wombat");
    assertUsage(parser, "help", "cache", "clear", "extra");
  }

  private CliParser parser() {
    return new CliParser(Map.of(), temporaryDirectory.resolve("home"));
  }

  private static void assertHelp(Object invocation, List<String> topic, boolean brief) {
    var help = assertInstanceOf(HelpInvocation.class, invocation);
    assertEquals(topic, help.topic());
    assertEquals(brief, help.brief());
  }

  private static void assertNoFileReadFailure(CliParser parser, String... arguments) {
    var failure = assertThrows(CliUsageException.class, () -> parser.parse(arguments));
    assertTrue(
        !failure.getMessage().startsWith("Cannot read"),
        () -> "help/version performed file I/O: " + failure.getMessage());
  }

  private static void assertMessageContains(
      CliParser parser, String expected, String... arguments) {
    var failure = assertThrows(CliUsageException.class, () -> parser.parse(arguments));
    assertTrue(failure.getMessage().contains(expected), failure::getMessage);
  }

  private static void assertUsage(CliParser parser, String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}
