package blater.nq.cli;

import java.util.Objects;

import blater.nq.inputreader.InputType;

/** How an invocation obtains hierarchical input data. */
public sealed interface InputSelection
    permits InputSelection.None, InputSelection.Automatic, InputSelection.Provided {
  /** This command has no input role. */
  record None() implements InputSelection {
  }

  /** Resolve redirected stdin versus no data from the execution environment. */
  record Automatic(InputType format) implements InputSelection {
    public Automatic {
      Objects.requireNonNull(format, "format");
    }
  }

  /** The user supplied a concrete source. */
  record Provided(DataInput input) implements InputSelection {
    public Provided {
      Objects.requireNonNull(input, "input");
    }
  }
}
