package blater.nq.report;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/** Serializes structured command reports independently from query result output. */
public final class ReportWriter {
  private ReportWriter() {
  }

  public static void write(ReportEnvelope report, ReportFormat format) {
    writeEnvelope(report.fields(), format, System.out, false);
  }

  public static void write(
      ReportEnvelope report,
      ReportFormat format,
      PrintStream stream) {
    writeEnvelope(report.fields(), format, stream, false);
  }

  public static void write(
      DiagnosticEnvelope diagnostic,
      ReportFormat format,
      PrintStream stream) {
    writeEnvelope(diagnostic.fields(), format, stream, true);
  }

  private static void writeEnvelope(
      Map<String, ?> fields,
      ReportFormat format,
      PrintStream stream,
      boolean diagnostic) {
    if (format == ReportFormat.JSON || format == ReportFormat.JSONL) {
      stream.println(ReportJsonRenderer.render(fields));
      return;
    }
    if (format == ReportFormat.YAML) {
      if (diagnostic) stream.println("---");
      stream.print(ReportYamlRenderer.render(fields));
      return;
    }
    if (format == ReportFormat.TOML) {
      stream.print(ReportTomlRenderer.render(fields));
      return;
    }
    stream.print(format.outputType().render(envelopeHierarchy(fields)));
  }

  private static Hierarchy envelopeHierarchy(Map<String, ?> fields) {
    Node root = new Node("");
    addFields(root, fields);
    return new Hierarchy(root, Hierarchy.RootKind.SYNTHETIC_OBJECT);
  }

  private static void addFields(Node parent, Map<String, ?> fields) {
    for (Map.Entry<String, ?> entry : fields.entrySet()) {
      addValue(parent, entry.getKey(), entry.getValue(), false);
    }
  }

  private static void addValue(Node parent, String name, Object value, boolean arrayItem) {
    if (value instanceof List<?> list) {
      Node collection = new Node(name);
      collection.setCollection(true);
      for (Object item : list) {
        addValue(collection, "", item, true);
      }
      parent.addNode(collection);
      return;
    }

    Node node = new Node(name);
    node.setArrayItem(arrayItem);
    if (value == null) {
      node.setNullValue(true);
    } else if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        addValue(node, entry.getKey().toString(), entry.getValue(), false);
      }
    } else {
      node.setValue(value.toString());
    }
    parent.addNode(node);
  }

}
