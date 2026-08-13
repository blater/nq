package blater.nq.cli;

import blater.nq.report.ReportFormat;

import java.util.Objects;
/** Inspect relations from input data, a cache, or an external database. */
public record CatalogInvocation(
    InputSelection input,
    CatalogPattern pattern,
    ExecutionTarget target,
    ReportFormat reportFormat,
    InvocationOptions options) implements NqInvocation {

  public CatalogInvocation {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(reportFormat, "reportFormat");
    Objects.requireNonNull(options, "options");
    if ((target instanceof ExecutionTarget.Temporary)
        != (input instanceof InputSelection.Provided)) {
      throw new IllegalArgumentException("A temporary catalog target requires exactly one input source");
    }
  }
}
