package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;

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
      case NAMED -> writeProperty(
          yaml,
          root.getName(),
          StructuredDataOutputMapper.nodeValue(root),
          0);
      case SYNTHETIC_OBJECT -> {
        Object value = StructuredDataOutputMapper.nodeValue(root);
        if (isEmptyCollection(value)) return "{}";
        writeValueBlock(yaml, value, 0);
      }
      case SYNTHETIC_ARRAY -> {
        List<Object> values = root.getChildren().stream()
            .map(StructuredDataOutputMapper::rootItemValue)
            .toList();
        if (values.isEmpty()) return "[]";
        writeList(yaml, values, 0);
      }
    }
    return yaml.toString();
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
    return DoubleQuotedStringEscaper.quote(value.toString());
  }

  private static String spaces(int count) {
    return " ".repeat(count);
  }

}
