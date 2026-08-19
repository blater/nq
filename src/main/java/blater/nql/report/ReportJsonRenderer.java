package blater.nql.report;

import java.util.List;
import java.util.Map;

/** Renders structured report values as compact JSON. */
final class ReportJsonRenderer {
  private ReportJsonRenderer() {
  }

  static String render(Object value) {
    if (value == null) {
      return "null";
    }
    if (value instanceof Boolean || value instanceof Number) {
      return value.toString();
    }
    if (value instanceof Map<?, ?> map) {
      return map(map);
    }
    if (value instanceof List<?> list) {
      return list(list);
    }
    return ReportStringEncoder.quote(value.toString());
  }

  private static String map(Map<?, ?> map) {
    StringBuilder result = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        result.append(",");
      }
      first = false;
      result.append(ReportStringEncoder.quote(entry.getKey().toString()))
          .append(":").append(render(entry.getValue()));
    }
    return result.append("}").toString();
  }

  private static String list(List<?> list) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < list.size(); index++) {
      if (index > 0) {
        result.append(",");
      }
      result.append(render(list.get(index)));
    }
    return result.append("]").toString();
  }
}
