package blater.nql.cli;

import blater.nql.outputwriter.OutputType;

import java.util.Objects;

/** Convert one hierarchical data source without a database. */
public record ConvertInvocation(
    DataInput input,
    OutputType output,
    InvocationOptions options) implements NqlInvocation {

  public ConvertInvocation {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(output, "output");
    Objects.requireNonNull(options, "options");
  }
}
