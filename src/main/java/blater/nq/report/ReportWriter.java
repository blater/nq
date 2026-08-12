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

  public static void write(Map<String, ?> fields, ReportFormat format) {
    write(fields, format, System.out);
  }

  public static void write(Map<String, ?> fields, ReportFormat format, PrintStream stream) {
    if (format == ReportFormat.JSON || format == ReportFormat.JSONL) {
      stream.println(json(Map.of("report", fields)));
      return;
    }
    stream.print(format.outputType().render(hierarchy(fields)));
  }

  private static Hierarchy hierarchy(Map<String, ?> fields) {
    Node root = new Node("report");
    addFields(root, fields);
    return new Hierarchy(root);
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

  private static String json(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean || value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      StringBuilder result = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) result.append(",");
        first = false;
        result.append(quoted(entry.getKey().toString())).append(":").append(json(entry.getValue()));
      }
      return result.append("}").toString();
    }
    if (value instanceof List<?> list) {
      StringBuilder result = new StringBuilder("[");
      for (int index = 0; index < list.size(); index++) {
        if (index > 0) result.append(",");
        result.append(json(list.get(index)));
      }
      return result.append("]").toString();
    }
    return quoted(value.toString());
  }

  private static String quoted(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\b' -> result.append("\\b");
        case '\f' -> result.append("\\f");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (ch < 0x20) result.append(String.format("\\u%04x", (int) ch));
          else result.append(ch);
        }
      }
    }
    return result.append("\"").toString();
  }
}
