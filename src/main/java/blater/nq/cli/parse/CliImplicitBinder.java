package blater.nq.cli.parse;

import blater.nq.cli.CacheInvocation;
import blater.nq.cli.CacheNameSelection;
import blater.nq.cli.NqInvocation;
import blater.nq.report.ReportFormat;

/** Classifies and binds command-less compatibility invocations. */
final class CliImplicitBinder {
  private CliImplicitBinder() {
  }

  static NqInvocation bind(CliBindingSupport support, CliParser.RawArguments raw) {
    return switch (implicitCommand(raw)) {
      case RUN -> CliRunBinder.bind(support, raw);
      case CACHE -> bindCacheLoad(support, raw);
      case CONVERT -> CliConvertBinder.bind(support, raw);
      default -> throw new IllegalStateException("Invalid implicit command");
    };
  }

  static CliParser.Command implicitCommand(CliParser.RawArguments raw) {
    boolean hasNamedScript = raw.scriptFile != null || raw.scriptText != null;
    boolean hasNamedInput = raw.inputFile != null || raw.inputText != null;
    boolean positionalScript = raw.positionals.stream().anyMatch(CliParser::isScriptFilename);
    boolean positionalData = raw.positionals.stream().anyMatch(CliParser::isDataFilename);
    boolean run = hasNamedScript || positionalScript || raw.positionals.size() > 1
        || hasNamedInput && !raw.positionals.isEmpty()
        || !hasNamedInput && !positionalData && !raw.positionals.isEmpty();
    if (run) {
      return CliParser.Command.RUN;
    }
    return raw.cache ? CliParser.Command.CACHE : CliParser.Command.CONVERT;
  }

  private static CacheInvocation.Load bindCacheLoad(
      CliBindingSupport support, CliParser.RawArguments raw) {
    CliParser.reject(raw.pattern != null, "--pattern is only valid for catalog");
    CliParser.reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    CliParser.reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for cache load");
    CliParser.reject(CliValueParser.hasJdbc(raw),
        "database options are not valid for cache load");
    CliParser.reject(raw.output != null,
        "cache loading uses --report-format, not --output");
    if (raw.positionals.size() > 1) {
      throw CliParser.usage("implicit cache loading accepts one data source");
    }
    String positional = raw.positionals.isEmpty() ? null : raw.positionals.getFirst();
    return new CacheInvocation.Load(
        support.dataInput(raw, positional, true),
        raw.name == null
            ? new CacheNameSelection.Generated()
            : new CacheNameSelection.Named(CliValueParser.cacheName(raw.name)),
        support.cacheDirectory(raw),
        raw.reportFormat == null
            ? ReportFormat.MARKDOWN
            : CliValueParser.reportFormat(raw.reportFormat),
        support.invocationOptions(raw));
  }
}
