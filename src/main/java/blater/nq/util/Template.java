package blater.nq.util;

import java.util.Map;

/*
 * Responsibility: Expands ${name} placeholders in script SQL and
 * mapping expressions from runtime parameters and system properties.
 */
public final class Template {
  private static final String PLACEHOLDER_START = "${";

  private Template() {
  }

  public static String expand(String template, Map<String, String> parameters) {
    if (template == null) {
      return null;
    }

    StringBuilder out = new StringBuilder(template.length());

    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);

      if (escapesPlaceholder(template, i)) {
        out.append(PLACEHOLDER_START);
        i += PLACEHOLDER_START.length();
        continue;
      }

      if (startsPlaceholder(template, i)) {
        int end = template.indexOf('}', i + PLACEHOLDER_START.length());
        if (end < 0) {
          out.append(c);
          continue;
        }

        String value = resolveValue(template.substring(i + PLACEHOLDER_START.length(), end), parameters);
        if (value != null) {
          out.append(value);
        }
        i = end;
      } else {
        out.append(c);
      }
    }

    return out.toString();
  }

  private static boolean escapesPlaceholder(String template, int index) {
    return template.charAt(index) == '\\'
        && startsPlaceholder(template, index + 1);
  }

  private static boolean startsPlaceholder(String template, int index) {
    return template.startsWith(PLACEHOLDER_START, index);
  }

  private static String resolveValue(String expression, Map<String, String> parameters) {
    String[] parts = expression.split(":", 2);
    String key = parts[0];
    String defaultValue = parts.length == 2 ? parts[1] : null;

    String value = parameters == null ? null : parameters.get(key);
    if (value == null) {
      value = System.getProperty(key);
    }
    return value == null ? defaultValue : value;
  }
}
