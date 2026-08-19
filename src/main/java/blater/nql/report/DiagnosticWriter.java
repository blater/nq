package blater.nql.report;

import java.io.PrintStream;
import java.util.Map;
import java.util.Objects;

/** Stateful serializer that keeps a sequence of diagnostic events well formed. */
public final class DiagnosticWriter implements AutoCloseable {
  private final ReportFormat format;
  private final PrintStream stream;
  private boolean started;
  private boolean closed;

  public DiagnosticWriter(ReportFormat format, PrintStream stream) {
    this.format = Objects.requireNonNull(format, "format");
    this.stream = Objects.requireNonNull(stream, "stream");
  }

  public void write(DiagnosticEnvelope diagnostic) {
    Objects.requireNonNull(diagnostic, "diagnostic");
    if (closed) throw new IllegalStateException("Diagnostic writer is closed");
    Map<String, ?> fields = diagnostic.fields();
    switch (format) {
      case JSON, JSONL, YAML -> ReportWriter.write(diagnostic, format, stream);
      case XML -> writeXml(fields);
      case TOML -> writeToml(fields);
      case CSV -> writeDelimited(fields, ',');
      case TSV -> writeDelimited(fields, '\t');
      case MARKDOWN -> writeMarkdown(fields);
    }
    started = true;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (started && format == ReportFormat.XML) {
      stream.println("</diagnostics>");
    }
  }

  private void writeXml(Map<String, ?> fields) {
    if (!started) stream.println("<diagnostics>");
    stream.println("  <diagnostic>");
    for (Map.Entry<String, ?> field : fields.entrySet()) {
      stream.print("    <");
      stream.print(field.getKey());
      stream.print(">");
      stream.print(xml(String.valueOf(field.getValue())));
      stream.print("</");
      stream.print(field.getKey());
      stream.println(">");
    }
    stream.println("  </diagnostic>");
  }

  private void writeToml(Map<String, ?> fields) {
    if (started) stream.println();
    stream.println("[[diagnostic]]");
    for (Map.Entry<String, ?> field : fields.entrySet()) {
      stream.print(field.getKey());
      stream.print(" = ");
      Object value = field.getValue();
      stream.println(value instanceof Number || value instanceof Boolean
          ? value : quoted(String.valueOf(value)));
    }
  }

  private void writeDelimited(Map<String, ?> fields, char delimiter) {
    if (!started) {
      stream.println(String.join(String.valueOf(delimiter),
          "schema_version", "code", "level", "message", "usage"));
    }
    stream.println(String.join(String.valueOf(delimiter),
        delimited(fields.get("schema_version"), delimiter),
        delimited(fields.get("code"), delimiter),
        delimited(fields.get("level"), delimiter),
        delimited(fields.get("message"), delimiter),
        delimited(fields.get("usage"), delimiter)));
  }

  private void writeMarkdown(Map<String, ?> fields) {
    if (!started) {
      stream.println("| schema_version | code | level | message | usage |");
      stream.println("| ---: | --- | --- | --- | --- |");
    }
    stream.printf("| %s | %s | %s | %s | %s |%n",
        markdown(fields.get("schema_version")),
        markdown(fields.get("code")),
        markdown(fields.get("level")),
        markdown(fields.get("message")),
        markdown(fields.get("usage")));
  }

  private static String delimited(Object value, char delimiter) {
    if (value == null) return "";
    String text = String.valueOf(value);
    if (text.indexOf(delimiter) < 0 && text.indexOf('"') < 0
        && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
      return text;
    }
    return "\"" + text.replace("\"", "\"\"") + "\"";
  }

  private static String markdown(Object value) {
    if (value == null) return "";
    return String.valueOf(value)
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", "<br>");
  }

  private static String xml(String value) {
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t") + "\"";
  }
}
