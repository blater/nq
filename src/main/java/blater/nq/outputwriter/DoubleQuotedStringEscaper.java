package blater.nq.outputwriter;

/*
 * Responsibility: Produces the compatible double-quoted string form used by
 * the handwritten YAML and TOML serializers.
 */
final class DoubleQuotedStringEscaper {
  private DoubleQuotedStringEscaper() {
  }

  static String quote(String value) {
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
          if (ch < 0x20 || ch == 0x7f) {
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
