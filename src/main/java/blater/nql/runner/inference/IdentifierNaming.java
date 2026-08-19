package blater.nql.runner.inference;

/** Shared normalized singular/plural vocabulary for schema and output inference. */
final class IdentifierNaming {
  private IdentifierNaming() {
  }

  static String normalize(String value) {
    return DatabaseStructure.normalize(value);
  }

  static String singular(String value) {
    String normalized = normalize(value);
    if (normalized.endsWith("ies") && normalized.length() > 3) {
      return normalized.substring(0, normalized.length() - 3) + "y";
    }
    if (normalized.endsWith("ses") && normalized.length() > 3) {
      return normalized.substring(0, normalized.length() - 2);
    }
    if (normalized.endsWith("s") && normalized.length() > 1) {
      return normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}
