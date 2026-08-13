package blater.nq.cli;

import blater.nq.outputwriter.OutputType;

import java.util.Objects;

/** Convert one hierarchical data source without a database. */
public record ConvertInvocation(
    DataInput input,
    OutputType output,
    InvocationOptions options) implements NqInvocation {

  public ConvertInvocation {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(options, "options");
  }
}
