package blater.nql.cli.parse;

/** A command-line grammar or validation failure (normative process exit 2). */
public final class CliUsageException extends IllegalArgumentException {
  private final String usage;

  public CliUsageException(String message) {
    this(message, "");
  }

  public CliUsageException(String message, String usage) {
    super(message);
    this.usage = usage == null ? "" : usage;
  }

  public CliUsageException(String message, Throwable cause) {
    super(message, cause);
    this.usage = "";
  }

  public String usage() {
    return usage;
  }
}
