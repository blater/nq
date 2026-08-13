package blater.nq.cli.parse;

import blater.nq.cli.CapabilitiesInvocation;
import blater.nq.report.ReportFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserCapabilitiesTest {
  private final CliParser parser = new CliParser();

  @Test
  void commandDefaultsToJson() {
    CapabilitiesInvocation invocation = assertInstanceOf(
        CapabilitiesInvocation.class, parser.parse("capabilities"));

    assertEquals(ReportFormat.JSON, invocation.reportFormat());
  }

  @Test
  void rootFlagIsAnEquivalentAlias() {
    CapabilitiesInvocation invocation = assertInstanceOf(
        CapabilitiesInvocation.class,
        parser.parse("--capabilities", "--report-format", "YaMl"));

    assertEquals(ReportFormat.YAML, invocation.reportFormat());
  }

  @Test
  void commandNameIsCaseInsensitive() {
    assertInstanceOf(CapabilitiesInvocation.class, parser.parse("CaPaBiLiTiEs"));
  }

  @Test
  void rejectsOperandsAndUnownedOptions() {
    assertThrows(CliUsageException.class, () -> parser.parse("capabilities", "extra"));
    assertThrows(CliUsageException.class, () -> parser.parse("capabilities", "--debug"));
    assertThrows(CliUsageException.class, () -> parser.parse("--capabilities", "--output", "json"));
  }

  @Test
  void rejectsConfigWithoutTryingToReadIt() {
    CliUsageException failure = assertThrows(
        CliUsageException.class,
        () -> parser.parse("capabilities", "--config", "/does/not/exist.properties"));

    assertEquals("capabilities accepts only --report-format", failure.getMessage());
  }
}
