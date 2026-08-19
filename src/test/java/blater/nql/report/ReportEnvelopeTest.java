package blater.nql.report;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportEnvelopeTest {
  @Test
  void successfulReportHasFrozenOuterShapeAndTypedJsonScalars() {
    var report = new ReportEnvelope("cache.list", Map.of("cleared", 2, "active", true));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ReportWriter.write(report, ReportFormat.JSON, new PrintStream(bytes));
    String json = bytes.toString(StandardCharsets.UTF_8);

    assertTrue(json.startsWith("{\"schema_version\":1"));
    assertFalse(json.contains("\"report\":"));
    assertTrue(json.contains("\"schema_version\":1"));
    assertTrue(json.contains("\"status\":\"ok\""));
    assertTrue(json.contains("\"command\":\"cache.list\""));
    assertTrue(json.contains("\"details\":"));
    assertTrue(json.contains("\"active\":true"));
    assertTrue(json.contains("\"cleared\":2"));
  }

  @Test
  void diagnosticOmitsRatherThanNullsAbsentUsage() {
    var diagnostic = new DiagnosticEnvelope(
        "NQL-CLI-UNKNOWN-OPTION", DiagnosticEnvelope.Level.ERROR,
        "Unknown option: --ouptut", new DiagnosticEnvelope.Usage.None());

    assertEquals(1, diagnostic.fields().get("schema_version"));
    assertEquals("error", diagnostic.fields().get("level"));
    assertFalse(diagnostic.fields().containsKey("usage"));
  }

  @Test
  void diagnosticIncludesExplicitUsageAndRejectsUnstableIdentifiers() {
    var diagnostic = new DiagnosticEnvelope(
        "NQL-CLI-MISSING-OPERAND", DiagnosticEnvelope.Level.ERROR,
        "run requires a script",
        new DiagnosticEnvelope.Usage.Present("nql run <script> [data] [options]"));

    assertEquals("nql run <script> [data] [options]", diagnostic.fields().get("usage"));
    assertThrows(IllegalArgumentException.class,
        () -> new ReportEnvelope("cache list", Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new DiagnosticEnvelope(
            "unknown", DiagnosticEnvelope.Level.ERROR, "bad",
            new DiagnosticEnvelope.Usage.None()));
  }

  @Test
  void yamlAndTomlPreserveNumericAndBooleanEnvelopeScalars() {
    var report = new ReportEnvelope("cache.list", Map.of("cleared", 2, "active", true));

    ByteArrayOutputStream yamlBytes = new ByteArrayOutputStream();
    ReportWriter.write(report, ReportFormat.YAML, new PrintStream(yamlBytes));
    String yaml = yamlBytes.toString(StandardCharsets.UTF_8);
    assertTrue(yaml.contains("schema_version: 1"));
    assertTrue(yaml.contains("cleared: 2"));
    assertTrue(yaml.contains("active: true"));

    ByteArrayOutputStream tomlBytes = new ByteArrayOutputStream();
    ReportWriter.write(report, ReportFormat.TOML, new PrintStream(tomlBytes));
    String toml = tomlBytes.toString(StandardCharsets.UTF_8);
    assertTrue(toml.contains("schema_version = 1"));
    assertTrue(toml.contains("cleared = 2"));
    assertTrue(toml.contains("active = true"));
  }
}
