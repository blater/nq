package blater.nql.cli.parse;

import blater.nql.cli.CapabilitiesInvocation;
import blater.nql.cli.NqlInvocation;
import blater.nql.cli.VersionInvocation;
import blater.nql.report.ReportFormat;

/** Binds root flags and non-operational commands before command dispatch. */
final class CliRootInvocationBinder {
  private CliRootInvocationBinder() {
  }

  static NqlInvocation bindIfApplicable(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    if (raw.capabilities) {
      validateCapabilitiesFlag(command, raw);
      return capabilities(raw);
    }
    if (raw.version) {
      validateVersionFlag(command, raw);
      return new VersionInvocation();
    }
    if (command == CliParser.Command.HELP || raw.help || raw.briefHelp) {
      return CliHelpBinder.bind(command, subcommand, raw);
    }
    if (command == CliParser.Command.VERSION) {
      CliOptionValidator.requireNoOperands(raw, "version");
      CliOptionValidator.rejectNonHelpOptions(raw, "version");
      return new VersionInvocation();
    }
    if (command == CliParser.Command.CAPABILITIES) {
      CliOptionValidator.requireNoOperands(raw, "capabilities");
      CliOptionValidator.validateCapabilitiesOptionOwnership(raw);
      return capabilities(raw);
    }
    return null;
  }

  private static void validateCapabilitiesFlag(
      CliParser.Command command, CliParser.RawArguments raw) {
    if (command != CliParser.Command.IMPLICIT || !raw.positionals.isEmpty()
        || raw.help || raw.briefHelp || raw.version
        || CliOptionValidator.hasNonCapabilityOptions(raw)) {
      throw CliParser.usage(
          "--capabilities is only valid as a root invocation with --report-format");
    }
  }

  private static void validateVersionFlag(
      CliParser.Command command, CliParser.RawArguments raw) {
    if (command != CliParser.Command.IMPLICIT || !raw.positionals.isEmpty()
        || raw.help || raw.briefHelp || CliOptionValidator.hasNonHelpOptions(raw)) {
      throw CliParser.usage("--version is only valid as a root invocation");
    }
  }

  private static CapabilitiesInvocation capabilities(CliParser.RawArguments raw) {
    ReportFormat format = raw.reportFormat == null
        ? ReportFormat.JSON
        : CliValueParser.reportFormat(raw.reportFormat);
    return new CapabilitiesInvocation(format);
  }
}
