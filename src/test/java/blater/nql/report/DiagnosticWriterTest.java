package blater.nql.report;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticWriterTest {
  @Test
  void xmlWrapsMultipleEventsInOneDocument() throws Exception {
    String output = writeTwo(ReportFormat.XML);
    var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));

    assertEquals("diagnostics", document.getDocumentElement().getTagName());
    assertEquals(2, document.getElementsByTagName("diagnostic").getLength());
  }

  @Test
  void lineAndDocumentFormatsFrameMultipleEventsIndependently() {
    assertEquals(2, writeTwo(ReportFormat.JSON).lines().count());
    assertEquals(2, writeTwo(ReportFormat.JSONL).lines().count());
    assertEquals(2, occurrences(writeTwo(ReportFormat.YAML), "---\n"));
    assertEquals(2, occurrences(writeTwo(ReportFormat.TOML), "[[diagnostic]]"));
  }

  @Test
  void tableFormatsWriteOneHeaderAndOneRowPerEvent() {
    assertEquals(3, writeTwo(ReportFormat.CSV).lines().count());
    assertEquals(3, writeTwo(ReportFormat.TSV).lines().count());
    String markdown = writeTwo(ReportFormat.MARKDOWN);
    assertEquals(4, markdown.lines().count());
    assertTrue(markdown.contains("first \\| warning"));
  }

  private static String writeTwo(ReportFormat format) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (var writer = new DiagnosticWriter(format, new PrintStream(bytes))) {
      writer.write(diagnostic("NQL-RUNTIME-WARNING", "first | warning"));
      writer.write(diagnostic("NQL-EXECUTION-FAILED", "then failed"));
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static DiagnosticEnvelope diagnostic(String code, String message) {
    return new DiagnosticEnvelope(
        code, DiagnosticEnvelope.Level.ERROR, message,
        new DiagnosticEnvelope.Usage.None());
  }

  private static int occurrences(String value, String fragment) {
    return (value.length() - value.replace(fragment, "").length()) / fragment.length();
  }
}
