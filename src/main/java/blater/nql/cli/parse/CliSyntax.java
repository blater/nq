package blater.nql.cli.parse;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates CLI token forms that picocli would otherwise accept permissively. */
final class CliSyntax {
  private CliSyntax() {
  }

  static void validate(
      String[] arguments,
      Set<String> valueOptions,
      Map<String, String> booleanOptions) {
    Set<String> seenBooleans = new HashSet<>();
    boolean options = true;
    for (int index = 0; index < arguments.length; index++) {
      String token = arguments[index];
      if (options && token.equals("--")) {
        options = false;
      } else if (options) {
        validateOptionForm(token);
        rejectDuplicateBoolean(token, booleanOptions, seenBooleans);
        if (valueOptions.contains(token) && index + 1 < arguments.length) {
          index++;
        }
      }
    }
  }

  static boolean containsHelpFlag(List<String> arguments) {
    return arguments.contains("--help") || arguments.contains("-h");
  }

  private static void validateOptionForm(String token) {
    if (token.startsWith("--") && token.contains("=")) {
      throw usage("Long options do not accept '=' syntax: " + token);
    }
    if (token.startsWith("-") && !token.startsWith("--") && token.length() > 2) {
      throw usage("Short options do not accept attached values or bundles: " + token);
    }
  }

  private static void rejectDuplicateBoolean(
      String token, Map<String, String> booleanOptions, Set<String> seenBooleans) {
    String booleanName = booleanOptions.get(token);
    if (booleanName != null && !seenBooleans.add(booleanName)) {
      throw usage("Duplicate option: " + token);
    }
  }

  private static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }
}
