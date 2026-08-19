package blater.nql.cli.parse;

import blater.nql.cli.CacheInvocation;
import blater.nql.cli.CacheNameSelection;
import blater.nql.cli.DataInput;
import blater.nql.report.ReportFormat;

import java.util.ArrayList;
import java.util.List;

/** Binds explicit cache subcommands into typed invocations. */
final class CliCacheBinder {
  private CliCacheBinder() {
  }

  static CacheInvocation bind(
      CliBindingSupport support, String subcommand, CliParser.RawArguments raw) {
    CliOptionValidator.validateCacheOptionOwnership(subcommand, raw);
    ReportFormat format = raw.reportFormat == null
        ? ReportFormat.MARKDOWN
        : CliValueParser.reportFormat(raw.reportFormat);
    return switch (subcommand) {
      case "load" -> bindLoad(support, raw, format);
      case "use" -> bindUse(support, raw, format);
      case "list" -> bindList(support, raw, format);
      case "clear" -> bindClear(support, raw, format);
      default -> throw CliParser.usage("Unknown cache subcommand: " + subcommand);
    };
  }

  private static CacheInvocation.Load bindLoad(
      CliBindingSupport support, CliParser.RawArguments raw, ReportFormat format) {
    CliParser.reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for cache load");
    CliParser.reject(raw.inputFile != null && raw.inputText != null,
        "cache load accepts one data source");
    CacheLoadOperands operands = loadOperands(raw);
    DataInput input = support.dataInput(raw, operands.data(), true);
    support.validateParquetOptions(raw, input.format());
    return new CacheInvocation.Load(
        input,
        operands.name() == null
            ? new CacheNameSelection.Generated()
            : new CacheNameSelection.Named(CliValueParser.cacheName(operands.name())),
        support.cacheDirectory(raw), format, support.invocationOptions(raw));
  }

  private static CacheLoadOperands loadOperands(CliParser.RawArguments raw) {
    String data = null;
    String name = raw.name;
    List<String> unknown = new ArrayList<>();
    for (String positional : raw.positionals) {
      if (CliParser.isDataFilename(positional)) {
        CliParser.reject(data != null || raw.inputFile != null || raw.inputText != null,
            "cache load accepts one data source");
        data = positional;
      } else {
        unknown.add(positional);
      }
    }
    if (!unknown.isEmpty() && data == null
        && raw.inputFile == null && raw.inputText == null && unknown.size() > 1) {
      data = unknown.removeFirst();
    }
    if (!unknown.isEmpty()) {
      CliParser.reject(name != null, "positional cache name conflicts with --name");
      name = unknown.removeFirst();
    }
    CliParser.reject(!unknown.isEmpty(), "cache load accepts at most data and cache name");
    return new CacheLoadOperands(data, name);
  }

  private static CacheInvocation.Use bindUse(
      CliBindingSupport support, CliParser.RawArguments raw, ReportFormat format) {
    support.rejectDataOptions(raw, "cache use");
    CliParser.reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for cache use");
    String name = support.singleName(raw, "cache use");
    return new CacheInvocation.Use(
        CliValueParser.cacheName(name), support.cacheDirectory(raw), format, raw.debug);
  }

  private static CacheInvocation.ListCaches bindList(
      CliBindingSupport support, CliParser.RawArguments raw, ReportFormat format) {
    support.rejectDataOptions(raw, "cache list");
    CliParser.reject(
        raw.name != null || raw.all || raw.olderThan != null || !raw.positionals.isEmpty(),
        "cache list accepts no target");
    return new CacheInvocation.ListCaches(support.cacheDirectory(raw), format, raw.debug);
  }

  private static CacheInvocation.Clear bindClear(
      CliBindingSupport support, CliParser.RawArguments raw, ReportFormat format) {
    support.rejectDataOptions(raw, "cache clear");
    int namedModes = (raw.name == null ? 0 : 1)
        + (raw.all ? 1 : 0) + (raw.olderThan == null ? 0 : 1);
    CliParser.reject(namedModes > 1, "cache clear accepts exactly one target");
    return new CacheInvocation.Clear(
        clearTarget(raw), support.cacheDirectory(raw), format, raw.debug);
  }

  private static CacheInvocation.ClearTarget clearTarget(CliParser.RawArguments raw) {
    if (raw.all) {
      CliParser.reject(!raw.positionals.isEmpty(),
          "cache clear --all accepts no positional target");
      return new CacheInvocation.ClearTarget.All();
    }
    if (raw.olderThan != null) {
      CliParser.reject(!raw.positionals.isEmpty(),
          "cache clear --older-than accepts no positional target");
      return new CacheInvocation.ClearTarget.OlderThan(CliValueParser.age(raw.olderThan));
    }
    if (raw.name != null) {
      CliParser.reject(!raw.positionals.isEmpty(),
          "positional cache name conflicts with --name");
      return new CacheInvocation.ClearTarget.Name(CliValueParser.cacheName(raw.name));
    }
    return positionalClearTarget(raw.positionals);
  }

  private static CacheInvocation.ClearTarget positionalClearTarget(List<String> positionals) {
    if (positionals.size() == 1 && CliParser.equalsWord(positionals.getFirst(), "all")) {
      return new CacheInvocation.ClearTarget.All();
    }
    if (positionals.size() == 2 && CliParser.equalsWord(positionals.getFirst(), "olderthan")) {
      return new CacheInvocation.ClearTarget.OlderThan(CliValueParser.age(positionals.get(1)));
    }
    if (positionals.size() == 1) {
      return new CacheInvocation.ClearTarget.Name(CliValueParser.cacheName(positionals.getFirst()));
    }
    throw CliParser.usage("cache clear requires a cache name, olderthan <age>, or all");
  }

  private record CacheLoadOperands(String data, String name) {
  }
}
