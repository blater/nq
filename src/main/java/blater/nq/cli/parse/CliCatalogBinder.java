package blater.nq.cli.parse;

import blater.nq.cli.CatalogInvocation;
import blater.nq.cli.CatalogPattern;
import blater.nq.cli.DataInput;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.InputSelection;
import blater.nq.report.ReportFormat;

import java.util.ArrayList;
import java.util.List;

/** Binds catalog-specific arguments after parsing and ownership validation. */
final class CliCatalogBinder {
  private CliCatalogBinder() {
  }

  static CatalogInvocation bind(CliBindingSupport support, CliParser.RawArguments raw) {
    CliOptionValidator.validateCatalogOptionOwnership(raw);
    CliParser.reject(raw.name != null && !raw.cache, "--name requires --cache");
    CliParser.reject(raw.cache && CliValueParser.hasJdbc(raw),
        "catalog cannot combine --cache and JDBC");
    CliParser.reject(raw.inputFile != null && raw.inputText != null,
        "catalog accepts at most one data source");

    CatalogOperands operands = operands(raw);
    DataInput input = support.dataInput(raw, operands.data(), false);
    support.validateParquetOptions(
        raw, input == null ? support.implicitInputType(raw) : input.format());
    if (input == null) {
      CliParser.reject(raw.paramsFile != null || !raw.params.isEmpty(),
          "task parameters require catalog input data");
    }
    ExecutionTarget target = target(support, raw, input);
    return new CatalogInvocation(
        input == null
            ? new InputSelection.Automatic(support.implicitInputType(raw))
            : new InputSelection.Provided(input),
        operands.pattern() == null
            ? new CatalogPattern.All()
            : new CatalogPattern.Matching(operands.pattern()),
        target,
        raw.reportFormat == null
            ? ReportFormat.MARKDOWN
            : CliValueParser.reportFormat(raw.reportFormat),
        support.invocationOptions(raw));
  }

  private static CatalogOperands operands(CliParser.RawArguments raw) {
    String data = null;
    List<String> unknown = new ArrayList<>();
    for (String positional : raw.positionals) {
      if (CliParser.isDataFilename(positional)) {
        if (data != null || raw.inputFile != null || raw.inputText != null) {
          throw CliParser.usage("catalog accepts one data source");
        }
        data = positional;
      } else {
        unknown.add(positional);
      }
    }
    if (unknown.size() > 1) {
      if (data == null && raw.inputFile == null && raw.inputText == null) {
        data = unknown.removeFirst();
      } else {
        throw CliParser.usage("catalog accepts at most a data source and pattern");
      }
    }
    String pattern = raw.pattern;
    if (!unknown.isEmpty()) {
      CliParser.reject(pattern != null, "positional pattern conflicts with --pattern");
      pattern = unknown.getFirst();
    }
    return new CatalogOperands(data, pattern);
  }

  private static ExecutionTarget target(
      CliBindingSupport support, CliParser.RawArguments raw, DataInput input) {
    if (input != null) {
      CliParser.reject(raw.cache || CliValueParser.hasJdbc(raw),
          "catalog data conflicts with another source");
      CliParser.reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for temporary catalog execution");
      return new ExecutionTarget.Temporary();
    }
    if (raw.cache) {
      return raw.name == null
          ? new ExecutionTarget.ActiveCache(support.cacheDirectory(raw))
          : new ExecutionTarget.NamedCache(
              support.cacheDirectory(raw), CliValueParser.cacheName(raw.name));
    }
    if (CliValueParser.hasJdbc(raw)) {
      CliParser.reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for JDBC catalog execution");
      return new ExecutionTarget.Jdbc(CliValueParser.jdbcConnection(raw));
    }
    return new ExecutionTarget.InputOrActiveCache(support.cacheDirectory(raw));
  }

  private record CatalogOperands(String data, String pattern) {
  }
}
