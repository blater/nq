package blater.nq.outputwriter;

/** Encodes JSON string literals independently of hierarchy traversal. */
final class JsonStringEncoder {
  private JsonStringEncoder() {
  }

  static String quote(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2);
    escaped.append('"');
    for (int index = 0; index < value.length(); index++) {
      appendEscaped(escaped, value.charAt(index));
    }
    escaped.append('"');
    return escaped.toString();
  }

  private static void appendEscaped(StringBuilder escaped, char character) {
    switch (character) {
      case '"' -> escaped.append("\\\"");
      case '\\' -> escaped.append("\\\\");
      case '\b' -> escaped.append("\\b");
      case '\f' -> escaped.append("\\f");
      case '\n' -> escaped.append("\\n");
      case '\r' -> escaped.append("\\r");
      case '\t' -> escaped.append("\\t");
      default -> appendLiteralOrControl(escaped, character);
    }
  }

  private static void appendLiteralOrControl(StringBuilder escaped, char character) {
    if (character < 0x20) {
      escaped.append(String.format("\\u%04x", (int) character));
    } else {
      escaped.append(character);
    }
  }
}
