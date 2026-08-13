package blater.nq.cli;

import blater.nq.outputwriter.OutputType;

import java.util.Objects;

/** Whether run output comes from the script/default or an explicit CLI override. */
public sealed interface OutputSelection
    permits OutputSelection.ScriptOrDefault, OutputSelection.Explicit {
  record ScriptOrDefault() implements OutputSelection {
  }

  record Explicit(OutputType format) implements OutputSelection {
    public Explicit {
      Objects.requireNonNull(format, "format");
    }
  }
}
