package blater.nql.cli;

import blater.nql.outputwriter.OutputType;

import java.util.Objects;
/** Run one complete NQL script against a selected database target. */
public record RunInvocation(
    ScriptSource script,
    InputSelection input,
    ExecutionTarget target,
    OutputSelection output,
    boolean noKeyInference,
    InvocationOptions options) implements NqlInvocation {

  public RunInvocation {
    Objects.requireNonNull(script, "script");
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(options, "options");
    if (target instanceof ExecutionTarget.Temporary && !(input instanceof InputSelection.Provided)) {
      throw new IllegalArgumentException("A temporary run target requires input data");
    }
    if (target instanceof ExecutionTarget.InputOrActiveCache
        && !(input instanceof InputSelection.Automatic)) {
      throw new IllegalArgumentException("An automatic run target requires automatic input selection");
    }
  }
}
