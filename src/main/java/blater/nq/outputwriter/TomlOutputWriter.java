package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Renders hierarchy maps and repeated objects as TOML tables
 * and arrays of tables. TOML has no null value, so null nodes become empty strings.
 */
public class TomlOutputWriter implements OutputWriter {
  @Override
  public void write(Hierarchy result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    System.out.print(map(result)); //NOPMD - suppressed SystemPrintln - legitimate CLI output
  }

  public static String map(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null) {
      return "";
    }

    Map<String, Object> document = new LinkedHashMap<>();
    switch (hierarchy.getRootKind()) {
      case NAMED -> document.put(root.getName(), StructuredDataOutputMapper.nodeValue(root));
      case SYNTHETIC_OBJECT -> addSyntheticObject(document, root);
      case SYNTHETIC_ARRAY -> document.put(
          "item",
          root.getChildren().stream()
              .map(StructuredDataOutputMapper::rootItemValue)
              .toList());
    }

    StringBuilder toml = new StringBuilder();
    writeTableBody(toml, document, List.of());
    return toml.toString();
  }

  private static void addSyntheticObject(Map<String, Object> document, Node root) {
    Object value = StructuredDataOutputMapper.nodeValue(root);
    if (value instanceof Map<?, ?> object) {
      for (Map.Entry<?, ?> entry : object.entrySet()) {
        document.put(entry.getKey().toString(), entry.getValue());
      }
    } else {
      document.put("value", value);
    }
  }

  private static void writeTableBody(
      StringBuilder toml,
      Map<String, Object> table,
      List<String> path) {
    for (Map.Entry<String, Object> entry : table.entrySet()) {
      if (!isTable(entry.getValue()) && !isArrayOfTables(entry.getValue())) {
        toml.append(key(entry.getKey()))
            .append(" = ")
            .append(formatValue(entry.getValue()))
            .append("\n");
      }
    }

    for (Map.Entry<String, Object> entry : table.entrySet()) {
      if (isTable(entry.getValue())) {
        List<String> childPath = append(path, entry.getKey());
        beginSection(toml);
        toml.append("[").append(formatPath(childPath)).append("]\n");
        writeTableBody(toml, asTable(entry.getValue()), childPath);
      }
    }

    for (Map.Entry<String, Object> entry : table.entrySet()) {
      if (isArrayOfTables(entry.getValue())) {
        List<String> childPath = append(path, entry.getKey());
        for (Object item : (List<?>) entry.getValue()) {
          beginSection(toml);
          toml.append("[[").append(formatPath(childPath)).append("]]\n");
          writeTableBody(toml, asTable(item), childPath);
        }
      }
    }
  }

  private static boolean isTable(Object value) {
    return value instanceof Map<?, ?>;
  }

  private static boolean isArrayOfTables(Object value) {
    return value instanceof List<?> list
        && !list.isEmpty()
        && list.stream().allMatch(TomlOutputWriter::isTable);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asTable(Object value) {
    return (Map<String, Object>) value;
  }

  private static String formatValue(Object value) {
    if (value == null) {
      return "\"\"";
    }
    if (value instanceof List<?> list) {
      return "[" + String.join(", ", list.stream()
          .map(TomlOutputWriter::formatValue)
          .toList()) + "]";
    }
    if (value instanceof Map<?, ?> map) {
      return "{" + String.join(", ", map.entrySet().stream()
          .map(entry -> key(entry.getKey().toString()) + " = " + formatValue(entry.getValue()))
          .toList()) + "}";
    }
    return DoubleQuotedStringEscaper.quote(value.toString());
  }

  private static String formatPath(List<String> path) {
    return String.join(".", path.stream().map(TomlOutputWriter::key).toList());
  }

  private static String key(String value) {
    return value.matches("[A-Za-z0-9_-]+")
        ? value
        : DoubleQuotedStringEscaper.quote(value);
  }

  private static List<String> append(List<String> path, String key) {
    java.util.ArrayList<String> result = new java.util.ArrayList<>(path);
    result.add(key);
    return result;
  }

  private static void beginSection(StringBuilder toml) {
    if (!toml.isEmpty() && toml.charAt(toml.length() - 1) == '\n') {
      toml.append("\n");
    }
  }
}
