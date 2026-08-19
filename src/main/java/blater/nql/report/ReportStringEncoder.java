package blater.nql.report;

/** Quotes strings shared by structured report renderers. */
final class ReportStringEncoder {
  private ReportStringEncoder() {
  }

  static String quote(String value) {
    StringBuilder result = new StringBuilder("\"");
    for (int index = 0; index < value.length(); index++) {
      appendEscaped(result, value.charAt(index));
    }
    return result.append("\"").toString();
  }

  private static void appendEscaped(StringBuilder result, char character) {
    switch (character) {
      case '"' -> result.append("\\\"");
      case '\\' -> result.append("\\\\");
      case '\b' -> result.append("\\b");
      case '\f' -> result.append("\\f");
      case '\n' -> result.append("\\n");
      case '\r' -> result.append("\\r");
      case '\t' -> result.append("\\t");
      default -> {
        if (character < 0x20) {
          result.append(String.format("\\u%04x", (int) character));
        } else {
          result.append(character);
        }
      }
    }
  }
}
