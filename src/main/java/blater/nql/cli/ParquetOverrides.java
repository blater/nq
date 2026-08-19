package blater.nql.cli;

import java.util.Objects;

/** Optional Parquet hierarchy-name overrides without Optional-valued constructors. */
public record ParquetOverrides(Value root, Value record) {
  public static final ParquetOverrides NONE = new ParquetOverrides(
      new Value.Inferred(), new Value.Inferred());

  public ParquetOverrides {
    Objects.requireNonNull(root, "root");
    Objects.requireNonNull(record, "record");
  }

  public sealed interface Value permits Value.Inferred, Value.Explicit {
    record Inferred() implements Value {
    }

    record Explicit(String value) implements Value {
      public Explicit {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
          throw new IllegalArgumentException("Parquet hierarchy name is blank");
        }
      }
    }
  }
}
