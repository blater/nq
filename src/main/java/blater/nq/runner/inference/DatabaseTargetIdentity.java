package blater.nq.runner.inference;

import blater.nq.ParameterParser;

import java.util.Map;

/** Stable cache identity derived from configuration and excluding JDBC passwords. */
public record DatabaseTargetIdentity(String identityText, String displayName) {
  public static DatabaseTargetIdentity from(Map<String, String> parameters) {
    String url = sanitizeUrl(parameters.get(ParameterParser.JDBC_DATABASE_PARAM));
    String driver = safe(parameters.get(ParameterParser.JDBC_DRIVER_PARAM));
    if (driver.isBlank()) {
      driver = safe(parameters.get(ParameterParser.JDBC_CLASS_NAME_PARAM));
    }
    String user = safe(parameters.get(ParameterParser.JDBC_USERNAME_PARAM));
    String identity = "url=" + url + "\n"
        + "driver=" + driver + "\n"
        + "user=" + user + "\n";
    return new DatabaseTargetIdentity(identity, (driver + " " + url).strip());
  }

  private static String safe(String value) {
    return value == null ? "" : value;
  }

  static String sanitizeUrl(String value) {
    String sanitized = safe(value)
        .replaceAll("(?i)(password|passwd|pwd)=([^;&?]*)", "$1=<redacted>");
    return sanitized.replaceAll(
        "(?i)(jdbc:oracle:[^:]+:)([^/@:]+)/[^@]+@",
        "$1$2/<redacted>@");
  }
}
