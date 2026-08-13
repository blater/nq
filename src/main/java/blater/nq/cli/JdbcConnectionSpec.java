package blater.nq.cli;

import java.util.Objects;
/** Effective JDBC connection settings after config and CLI precedence. */
public record JdbcConnectionSpec(
    String url,
    DriverSelection driver,
    Credentials credentials) {

  public JdbcConnectionSpec {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(driver, "driver");
    Objects.requireNonNull(credentials, "credentials");
    if (url.isBlank()) {
      throw new IllegalArgumentException("JDBC URL is blank");
    }
  }
}
