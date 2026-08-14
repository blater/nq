package blater.nq.report;

import java.util.List;
import java.util.Map;

/** Renders structured report values as YAML. */
final class ReportYamlRenderer {
  private ReportYamlRenderer() {
  }

  static String render(Map<String, ?> fields) {
    StringBuilder result = new StringBuilder();
    writeMap(result, fields, 0);
    return result.toString();
  }

  private static void writeMap(StringBuilder result, Map<?, ?> fields, int indent) {
    for (Map.Entry<?, ?> entry : fields.entrySet()) {
      result.append(" ".repeat(indent)).append(entry.getKey()).append(":");
      Object value = entry.getValue();
      if (isScalar(value) || isEmptyCollection(value)) {
        result.append(" ").append(scalar(value)).append("\n");
      } else {
        result.append("\n");
        writeValue(result, value, indent + 2);
      }
    }
  }

  private static void writeValue(StringBuilder result, Object value, int indent) {
    if (value instanceof Map<?, ?> map) {
      writeMap(result, map, indent);
    } else if (value instanceof List<?> list) {
      for (Object item : list) {
        result.append(" ".repeat(indent)).append("-");
        if (isScalar(item) || isEmptyCollection(item)) {
          result.append(" ").append(scalar(item)).append("\n");
        } else {
          result.append("\n");
          writeValue(result, item, indent + 2);
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

  private static String scalar(Object value) {
    if (value == null) return "null";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof Map<?, ?>) return "{}";
    if (value instanceof List<?>) return "[]";
    return ReportStringEncoder.quote(value.toString());
  }
}
