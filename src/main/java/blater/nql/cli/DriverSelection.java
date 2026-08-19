package blater.nql.cli;

import java.util.Objects;

/** JDBC driver discovery or an explicit implementation hint. */
public sealed interface DriverSelection
    permits DriverSelection.Automatic, DriverSelection.Known, DriverSelection.ClassName {
  record Automatic() implements DriverSelection {
  }

  record Known(String value) implements DriverSelection {
    public Known {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
        throw new IllegalArgumentException("Known JDBC driver is blank");
      }
    }
  }

  record ClassName(String value) implements DriverSelection {
    public ClassName {
      Objects.requireNonNull(value, "value");
      if (value.isBlank()) {
        throw new IllegalArgumentException("JDBC driver class is blank");
      }
    }
  }
}
