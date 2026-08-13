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
      stream.println(json(fields));
      return;
    }
    if (format == ReportFormat.YAML) {
      if (diagnostic) stream.println("---");
      stream.print(yaml(fields));
      return;
    }
    if (format == ReportFormat.TOML) {
      stream.print(toml(fields));
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

  private static String yaml(Map<String, ?> fields) {
    StringBuilder result = new StringBuilder();
    writeYamlMap(result, fields, 0);
    return result.toString();
  }

  private static void writeYamlMap(
      StringBuilder result,
      Map<?, ?> fields,
      int indent) {
    for (Map.Entry<?, ?> entry : fields.entrySet()) {
      result.append(" ".repeat(indent)).append(entry.getKey()).append(":");
      Object value = entry.getValue();
      if (isScalar(value) || isEmptyCollection(value)) {
        result.append(" ").append(yamlScalar(value)).append("\n");
      } else {
        result.append("\n");
        writeYamlValue(result, value, indent + 2);
      }
    }
  }

  private static void writeYamlValue(StringBuilder result, Object value, int indent) {
    if (value instanceof Map<?, ?> map) {
      writeYamlMap(result, map, indent);
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        result.append(" ".repeat(indent)).append("-");
        if (isScalar(item) || isEmptyCollection(item)) {
          result.append(" ").append(yamlScalar(item)).append("\n");
        } else {
          result.append("\n");
          writeYamlValue(result, item, indent + 2);
        }
      }
    }
  }

  private static boolean isScalar(Object value) {
    return value == null || value instanceof String
        || value instanceof Boolean || value instanceof Number;
  }

  private static boolean isEmptyCollection(Object value) {
    return value instanceof Map<?, ?> map && map.isEmpty()
        || value instanceof List<?> list && list.isEmpty();
  }

  private static String yamlScalar(Object value) {
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof Map<?, ?>) return "{}";
    if (value instanceof List<?>) return "[]";
    return quoted(value.toString());
  }

  private static String toml(Map<String, ?> fields) {
    StringBuilder result = new StringBuilder();
    writeTomlTable(result, fields, List.of());
    return result.toString();
  }

  private static void writeTomlTable(
      StringBuilder result,
      Map<?, ?> table,
      List<String> path) {
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?>) && !isArrayOfMaps(entry.getValue())) {
        result.append(tomlKey(entry.getKey().toString())).append(" = ")
            .append(tomlValue(entry.getValue())).append("\n");
      }
    }
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> child) {
        List<String> childPath = append(path, entry.getKey().toString());
        tomlSectionBreak(result);
        result.append("[").append(tomlPath(childPath)).append("]\n");
        writeTomlTable(result, child, childPath);
      }
    }
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (isArrayOfMaps(entry.getValue())) {
        List<String> childPath = append(path, entry.getKey().toString());
        for (Object item : (List<?>) entry.getValue()) {
          tomlSectionBreak(result);
          result.append("[[").append(tomlPath(childPath)).append("]]\n");
          writeTomlTable(result, (Map<?, ?>) item, childPath);
        }
      }
    }
  }

  private static boolean isArrayOfMaps(Object value) {
    return value instanceof List<?> list && !list.isEmpty()
        && list.stream().allMatch(Map.class::isInstance);
  }

  private static String tomlValue(Object value) {
    if (value == null) return "\"\"";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof List<?> list) {
      return "[" + String.join(", ", list.stream().map(ReportWriter::tomlValue).toList()) + "]";
    }
    return quoted(value.toString());
  }

  private static String tomlPath(List<String> path) {
    return String.join(".", path.stream().map(ReportWriter::tomlKey).toList());
  }

  private static String tomlKey(String key) {
    return key.matches("[A-Za-z0-9_-]+") ? key : quoted(key);
  }

  private static List<String> append(List<String> path, String value) {
    java.util.ArrayList<String> result = new java.util.ArrayList<>(path);
    result.add(value);
    return result;
  }

  private static void tomlSectionBreak(StringBuilder result) {
    if (!result.isEmpty() && result.charAt(result.length() - 1) == '\n') result.append("\n");
  }
}
