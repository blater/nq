package blater.nq.cli;

import blater.nq.report.ReportFormat;

import java.util.Objects;

/** Prints the versioned, machine-readable NQ capability contract. */
public record CapabilitiesInvocation(ReportFormat reportFormat) implements NqInvocation {
  public CapabilitiesInvocation {
    Objects.requireNonNull(reportFormat, "reportFormat");
  }
}
