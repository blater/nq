package blater.nql.cli;

import blater.nql.report.ReportFormat;

import java.util.Objects;

/** Prints the versioned, machine-readable NQL capability contract. */
public record CapabilitiesInvocation(ReportFormat reportFormat) implements NqlInvocation {
  public CapabilitiesInvocation {
    Objects.requireNonNull(reportFormat, "reportFormat");
  }
}
