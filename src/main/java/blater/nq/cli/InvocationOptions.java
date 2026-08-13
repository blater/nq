package blater.nq.cli;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Shared execution modifiers and task parameters. */
public record InvocationOptions(
    Map<String, String> parameters,
    boolean debug,
    ParquetOverrides parquetOverrides) {

  public static final InvocationOptions EMPTY = new InvocationOptions(
      Map.of(), false, ParquetOverrides.NONE);

  public InvocationOptions {
    Objects.requireNonNull(parameters, "parameters");
    parameters = Map.copyOf(new LinkedHashMap<>(parameters));
    Objects.requireNonNull(parquetOverrides, "parquetOverrides");
  }
}
