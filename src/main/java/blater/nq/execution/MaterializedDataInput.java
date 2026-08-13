package blater.nq.execution;

import blater.nq.cli.DataInput;

import java.nio.file.Path;
import java.util.Objects;

/** A non-file typed data source staged at a concrete path for file-based engine readers. */
public record MaterializedDataInput(DataInput input, Path path) {
  public MaterializedDataInput {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(path, "path");
  }
}
