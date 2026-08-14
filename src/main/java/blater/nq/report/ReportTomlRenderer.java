package blater.nq.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Renders structured report values as TOML tables. */
final class ReportTomlRenderer {
  private ReportTomlRenderer() {
  }

  static String render(Map<String, ?> fields) {
    StringBuilder result = new StringBuilder();
    writeTable(result, fields, List.of());
    return result.toString();
  }

  private static void writeTable(StringBuilder result, Map<?, ?> table, List<String> path) {
    writeScalars(result, table);
    writeChildTables(result, table, path);
    writeTableArrays(result, table, path);
  }

  private static void writeScalars(StringBuilder result, Map<?, ?> table) {
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?>) && !isArrayOfMaps(entry.getValue())) {
        result.append(key(entry.getKey().toString())).append(" = ")
            .append(value(entry.getValue())).append("\n");
      }
    }
  }

  private static void writeChildTables(
      StringBuilder result, Map<?, ?> table, List<String> path) {
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (entry.getValue() instanceof Map<?, ?> child) {
        List<String> childPath = append(path, entry.getKey().toString());
        sectionBreak(result);
        result.append("[").append(path(childPath)).append("]\n");
        writeTable(result, child, childPath);
      }
    }
  }

  private static void writeTableArrays(
      StringBuilder result, Map<?, ?> table, List<String> path) {
    for (Map.Entry<?, ?> entry : table.entrySet()) {
      if (!isArrayOfMaps(entry.getValue())) {
        continue;
      }
      List<String> childPath = append(path, entry.getKey().toString());
      for (Object item : (List<?>) entry.getValue()) {
        sectionBreak(result);
        result.append("[[").append(path(childPath)).append("]]\n");
        writeTable(result, (Map<?, ?>) item, childPath);
      }
    }
  }

  private static boolean isArrayOfMaps(Object value) {
    return value instanceof List<?> list && !list.isEmpty()
        && list.stream().allMatch(Map.class::isInstance);
  }

  private static String value(Object value) {
    if (value == null) return "\"\"";
    if (value instanceof Boolean || value instanceof Number) return value.toString();
    if (value instanceof List<?> list) {
      return "[" + String.join(", ", list.stream().map(ReportTomlRenderer::value).toList()) + "]";
    }
    return ReportStringEncoder.quote(value.toString());
  }

  private static String path(List<String> path) {
    return String.join(".", path.stream().map(ReportTomlRenderer::key).toList());
  }

  private static String key(String key) {
    return key.matches("[A-Za-z0-9_-]+") ? key : ReportStringEncoder.quote(key);
  }

  private static List<String> append(List<String> path, String value) {
    ArrayList<String> result = new ArrayList<>(path);
    result.add(value);
    return result;
  }

  private static void sectionBreak(StringBuilder result) {
    if (!result.isEmpty() && result.charAt(result.length() - 1) == '\n') {
      result.append("\n");
    }
  }
}
