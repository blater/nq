package blater.nql.cli;

import java.util.Objects;

/** JDBC credentials, including the distinction between absent and explicitly empty values. */
public record Credentials(Value username, Value password) {
  public static final Credentials UNSPECIFIED = new Credentials(
      new Value.Unspecified(), new Value.Unspecified());

  public Credentials {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
  }

  public sealed interface Value permits Value.Unspecified, Value.Specified {
    record Unspecified() implements Value {
    }

    record Specified(String value) implements Value {
      public Specified {
        Objects.requireNonNull(value, "value");
      }
    }
  }
}
