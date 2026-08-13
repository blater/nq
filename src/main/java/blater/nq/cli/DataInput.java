package blater.nq.cli;

import blater.nq.inputreader.InputType;

import java.util.Objects;

/** A data source paired with its effective input format. */
public record DataInput(DataSourceSpec source, InputType format) {
  public DataInput {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(format, "format");
  }
}
