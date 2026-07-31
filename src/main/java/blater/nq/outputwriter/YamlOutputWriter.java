package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Renders a hierarchy as YAML. XML-only node state such
 * as attributes is approximated as ordinary YAML properties.
 */
public class YamlOutputWriter implements OutputWriter {
  @Override
  public void write(Hierarchy result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    System.out.println(map(result)); //NOPMD - suppressed SystemPrintln - legitimate CLI output
  }

  public static String map(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null) {
      return "{}";
    }

    StringBuilder yaml = new StringBuilder();
    switch (hierarchy.getRootKind()) {
      case NAMED -> writeProperty(yaml, root.getName(), nodeValue(root), 0);
      case SYNTHETIC_OBJECT -> {
        Object value = nodeValue(root);
        if (isEmptyCollection(value)) return "{}";
        writeValueBlock(yaml, value, 0);
      }
      case SYNTHETIC_ARRAY -> {
        List<Object> values = root.getChildren().stream().map(YamlOutputWriter::rootItemValue).toList();
        if (values.isEmpty()) return "[]";
        writeList(yaml, values, 0);
      }
    }
    return yaml.toString();
  }

  private static Object nodeValue(Node node) {
    if (node.isCollection()) {
      return node.getChildren().stream().map(YamlOutputWriter::rootItemValue).toList();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.hasValue()) {
      return node.getValue();
    }

    Map<String, Object> object = new LinkedHashMap<>();
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      List<Node> children = entry.getValue();
      if (children.size() == 1) {
        object.put(entry.getKey(), nodeValue(children.getFirst()));
      } else {
        object.put(entry.getKey(), children.stream()
            .map(YamlOutputWriter::nodeValue)
            .toList());
      }
    }
    return object;
  }

  private static Object namedNodeValue(Node node) {
    Map<String, Object> named = new LinkedHashMap<>();
    named.put(node.getName(), nodeValue(node));
    return named;
  }

  private static Object rootItemValue(Node node) {
    return node.getName() == null || node.getName().isEmpty()
        ? nodeValue(node)
        : namedNodeValue(node);
  }

  private static void writeProperty(StringBuilder yaml, String key, Object value, int indent) {
    yaml.append(spaces(indent)).append(key).append(":");
    if (isScalar(value) || isEmptyCollection(value)) {
      yaml.append(" ").append(formatScalarOrEmpty(value)).append("\n");
      return;
    }
    yaml.append("\n");
    writeValueBlock(yaml, value, indent + 2);
  }

  @SuppressWarnings("unchecked")
  private static void writeValueBlock(StringBuilder yaml, Object value, int indent) {
    if (value instanceof Map<?, ?> object) {
      for (Map.Entry<String, Object> entry : ((Map<String, Object>) object).entrySet()) {
        writeProperty(yaml, entry.getKey(), entry.getValue(), indent);
      }
      return;
    }

    if (value instanceof List<?> list) {
      writeList(yaml, list, indent);
      return;
    }

    yaml.append(spaces(indent)).append(formatScalarOrEmpty(value)).append("\n");
  }

  private static void writeList(StringBuilder yaml, List<?> list, int indent) {
    for (Object item : list) {
      if (isScalar(item) || isEmptyCollection(item)) {
        yaml.append(spaces(indent)).append("- ").append(formatScalarOrEmpty(item)).append("\n");
      } else {
        yaml.append(spaces(indent)).append("-\n");
        writeValueBlock(yaml, item, indent + 2);
      }
    }
  }

  private static boolean isScalar(Object value) {
    return value == null || value instanceof String;
  }

  private static boolean isEmptyCollection(Object value) {
    return value instanceof Map<?, ?> map && map.isEmpty()
        || value instanceof List<?> list && list.isEmpty();
  }

  private static String formatScalarOrEmpty(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Map<?, ?>) {
      return "{}";
    }
    if (value instanceof List<?>) {
      return "[]";
    }
    return quote(value.toString());
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }

  private static String spaces(int count) {
    return " ".repeat(count);
  }

  private static String quote(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2);
    escaped.append('"');
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (ch < 0x20) {
            escaped.append(String.format("\\u%04x", (int) ch));
          } else {
            escaped.append(ch);
          }
        }
      }
    }
    escaped.append('"');
    return escaped.toString();
  }
}
