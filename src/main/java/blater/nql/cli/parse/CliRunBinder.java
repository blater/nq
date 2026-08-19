package blater.nql.cli.parse;

import blater.nql.cli.DataInput;
import blater.nql.cli.ExecutionTarget;
import blater.nql.cli.InputSelection;
import blater.nql.cli.OutputSelection;
import blater.nql.cli.RunInvocation;
import blater.nql.cli.ScriptSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Binds run-specific script, data, execution-target, and output options. */
final class CliRunBinder {
  private CliRunBinder() {
  }

  static RunInvocation bind(CliBindingSupport support, CliParser.RawArguments raw) {
    validate(raw);
    Operands operands = bindOperands(
        raw.positionals,
        raw.scriptFile != null || raw.scriptText != null,
        raw.inputFile != null || raw.inputText != null);
    ScriptSource script = scriptSource(raw, operands.script());
    if (script == null) {
      throw CliParser.usage("run requires a script");
    }
    DataInput data = support.dataInput(raw, operands.data(), false);
    support.validateParquetOptions(
        raw, data == null ? support.implicitInputType(raw) : data.format());
    return new RunInvocation(
        script,
        data == null
            ? new InputSelection.Automatic(support.implicitInputType(raw))
            : new InputSelection.Provided(data),
        target(support, raw, data),
        raw.output == null
            ? new OutputSelection.ScriptOrDefault()
            : new OutputSelection.Explicit(CliValueParser.outputType(raw.output)),
        raw.noKeyInference,
        support.invocationOptions(raw));
  }

  private static void validate(CliParser.RawArguments raw) {
    CliOptionValidator.validateRunOptionOwnership(raw);
    CliParser.reject(raw.name != null && !raw.cache, "--name requires --cache");
    CliParser.reject(raw.cache && CliValueParser.hasJdbc(raw),
        "run cannot combine --cache and JDBC");
    CliParser.reject(raw.scriptFile != null && raw.scriptText != null,
        "run accepts exactly one script source");
    CliParser.reject(raw.inputFile != null && raw.inputText != null,
        "run accepts at most one data source");
  }

  private static ExecutionTarget target(
      CliBindingSupport support, CliParser.RawArguments raw, DataInput data) {
    if (raw.cache) {
      return raw.name == null
          ? new ExecutionTarget.ActiveCache(support.cacheDirectory(raw))
          : new ExecutionTarget.NamedCache(
              support.cacheDirectory(raw), CliValueParser.cacheName(raw.name));
    }
    if (CliValueParser.hasJdbc(raw)) {
      CliParser.reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for JDBC execution");
      return new ExecutionTarget.Jdbc(CliValueParser.jdbcConnection(raw));
    }
    if (data != null) {
      CliParser.reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for temporary data execution");
      return new ExecutionTarget.Temporary();
    }
    return new ExecutionTarget.InputOrActiveCache(support.cacheDirectory(raw));
  }

  private static Operands bindOperands(
      List<String> positionals, boolean namedScript, boolean namedData) {
    String script = null;
    String data = null;
    List<String> unknown = new ArrayList<>();
    for (String positional : positionals) {
      if (CliParser.isScriptFilename(positional)) {
        CliParser.reject(script != null || namedScript,
            "positional script conflicts with another script source");
        script = positional;
      } else if (CliParser.isDataFilename(positional) || "-".equals(positional)) {
        CliParser.reject(data != null || namedData,
            "positional data conflicts with another data source");
        data = positional;
      } else {
        unknown.add(positional);
      }
    }
    for (String positional : unknown) {
      if (!namedScript && script == null) {
        script = positional;
      } else if (!namedData && data == null) {
        data = positional;
      } else {
        throw CliParser.usage("run has too many positional operands");
      }
    }
    return new Operands(script, data);
  }

  private static ScriptSource scriptSource(CliParser.RawArguments raw, String positional) {
    if (raw.scriptFile != null) {
      return new ScriptSource.File(Path.of(raw.scriptFile));
    }
    if (raw.scriptText != null) {
      return new ScriptSource.Text(raw.scriptText);
    }
    if (positional == null) {
      return null;
    }
    return CliParser.isScriptFilename(positional)
        ? new ScriptSource.File(Path.of(positional))
        : new ScriptSource.Text(positional);
  }

  private record Operands(String script, String data) {
  }
}
