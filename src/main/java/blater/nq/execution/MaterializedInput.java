package blater.nq.execution;

import blater.nq.cli.DataInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Execution-owned result of resolving and, when needed, staging typed input. */
public sealed interface MaterializedInput extends AutoCloseable
    permits MaterializedInput.None, MaterializedInput.Provided {
  @Override
  void close() throws IOException;

  record None() implements MaterializedInput {
    @Override public void close() {
    }
  }

  record Provided(DataInput input, Path path, Ownership ownership) implements MaterializedInput {
    public Provided {
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(ownership, "ownership");
      path = path.toAbsolutePath().normalize();
    }

    public MaterializedDataInput engineInput() {
      return new MaterializedDataInput(input, path);
    }

    @Override
    public void close() throws IOException {
      if (ownership == Ownership.TEMPORARY) Files.deleteIfExists(path);
    }
  }

  enum Ownership { BORROWED, TEMPORARY }
}
