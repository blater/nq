package blater.nql.cli.parse;

import blater.nql.cli.NqlInvocation;

/** Dispatches validated parsed arguments to command-specific binders. */
final class CliInvocationDispatcher {
  private final CliBindingSupport support;

  CliInvocationDispatcher(CliBindingSupport support) {
    this.support = support;
  }

  NqlInvocation bind(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    NqlInvocation rootInvocation =
        CliRootInvocationBinder.bindIfApplicable(command, subcommand, raw);
    if (rootInvocation != null) {
      return rootInvocation;
    }
    return switch (command) {
      case RUN -> CliRunBinder.bind(support, raw);
      case CONVERT -> CliConvertBinder.bind(support, raw);
      case CATALOG -> CliCatalogBinder.bind(support, raw);
      case CACHE -> CliCacheBinder.bind(support, subcommand, raw);
      case IMPLICIT -> CliImplicitBinder.bind(support, raw);
      case CAPABILITIES, HELP, VERSION -> throw new IllegalStateException("handled as root invocation");
    };
  }
}
