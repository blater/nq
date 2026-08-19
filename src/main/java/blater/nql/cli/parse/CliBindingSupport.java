package blater.nql.cli.parse;

import blater.nql.cli.DataInput;
import blater.nql.cli.DataSourceSpec;
import blater.nql.cli.InvocationOptions;
import blater.nql.cli.ParquetOverrides;
import blater.nql.inputreader.InputType;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Shared construction services used by command-specific invocation binders. */
final class CliBindingSupport {
  private final Supplier<Path> userHome;

  CliBindingSupport(Supplier<Path> userHome) {
    this.userHome = userHome;
  }

  DataInput dataInput(
      CliParser.RawArguments raw, String positional, boolean defaultStdin) {
    InputSource inputSource = inputSource(raw, positional, defaultStdin);
    if (inputSource == null) {
      return null;
    }
    InputType type = raw.inputFormat == null
        ? inferredInputType(inputSource.filename())
        : CliValueParser.inputType(raw.inputFormat);
    return new DataInput(inputSource.source(), type);
  }

  InputType implicitInputType(CliParser.RawArguments raw) {
    return raw.inputFormat == null
        ? InputType.JSON
        : CliValueParser.inputType(raw.inputFormat);
  }

  void validateParquetOptions(CliParser.RawArguments raw, InputType inputType) {
    if ((raw.parquetRoot != null || raw.parquetRecord != null)
        && inputType != InputType.PARQUET) {
      throw CliParser.usage("--parquet-root and --parquet-record require Parquet input");
    }
  }

  InvocationOptions invocationOptions(CliParser.RawArguments raw) {
    ParquetOverrides.Value root = raw.parquetRoot == null
        ? new ParquetOverrides.Value.Inferred()
        : new ParquetOverrides.Value.Explicit(raw.parquetRoot);
    ParquetOverrides.Value record = raw.parquetRecord == null
        ? new ParquetOverrides.Value.Inferred()
        : new ParquetOverrides.Value.Explicit(raw.parquetRecord);
    return new InvocationOptions(
        taskParameters(raw), raw.debug, new ParquetOverrides(root, record));
  }

  Path cacheDirectory(CliParser.RawArguments raw) {
    String configured = raw.cacheDirectory;
    if (configured == null || configured.isBlank()) {
      configured = userHome.get().resolve(".nql").resolve("cache").toString();
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  String singleName(CliParser.RawArguments raw, String command) {
    CliParser.reject(raw.name != null && !raw.positionals.isEmpty(),
        "positional cache name conflicts with --name");
    if (raw.positionals.size() > 1) {
      throw CliParser.usage(command + " accepts one cache name");
    }
    String name = raw.name != null ? raw.name
        : raw.positionals.isEmpty() ? null : raw.positionals.getFirst();
    if (name == null) {
      throw CliParser.usage(command + " requires a cache name");
    }
    return name;
  }

  void rejectDataOptions(CliParser.RawArguments raw, String command) {
    CliParser.reject(raw.inputFile != null || raw.inputText != null || raw.inputFormat != null,
        "input options are not valid for " + command);
    CliParser.reject(raw.paramsFile != null || !raw.params.isEmpty(),
        "parameters are not valid for " + command);
    CliParser.reject(raw.parquetRoot != null || raw.parquetRecord != null,
        "Parquet options are not valid for " + command);
  }

  private static InputSource inputSource(
      CliParser.RawArguments raw, String positional, boolean defaultStdin) {
    if (raw.inputFile != null) {
      return fileSource(raw.inputFile);
    }
    if (raw.inputText != null) {
      return new InputSource(new DataSourceSpec.Text(raw.inputText), null);
    }
    if (positional != null) {
      return positionalSource(positional);
    }
    return defaultStdin
        ? new InputSource(new DataSourceSpec.StandardInput(), null)
        : null;
  }

  private static InputSource fileSource(String filename) {
    DataSourceSpec source = "-".equals(filename)
        ? new DataSourceSpec.StandardInput()
        : new DataSourceSpec.File(Path.of(filename));
    return new InputSource(source, filename);
  }

  private static InputSource positionalSource(String positional) {
    if ("-".equals(positional)) {
      return new InputSource(new DataSourceSpec.StandardInput(), positional);
    }
    DataSourceSpec source = CliParser.isDataFilename(positional)
        ? new DataSourceSpec.File(Path.of(positional))
        : new DataSourceSpec.Text(positional);
    return new InputSource(source, positional);
  }

  private static InputType inferredInputType(String filename) {
    return filename != null && CliParser.isDataFilename(filename)
        ? InputType.fromFilename(filename)
        : InputType.JSON;
  }

  private static Map<String, String> taskParameters(CliParser.RawArguments raw) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (raw.paramsFile != null) {
      CliPropertyFiles.read(Path.of(raw.paramsFile), "parameters").forEach((name, value) -> {
        validateParameterName(name);
        parameters.put(name, value);
      });
    }
    applyCommandLineParameters(raw.params, parameters);
    return Map.copyOf(parameters);
  }

  private static void applyCommandLineParameters(
      Iterable<String> assignments, Map<String, String> parameters) {
    Set<String> commandLineNames = new HashSet<>();
    for (String assignment : assignments) {
      int equals = assignment.indexOf('=');
      if (equals <= 0) {
        throw CliParser.usage(
            "--param requires a non-empty name=value assignment: " + assignment);
      }
      String name = assignment.substring(0, equals);
      validateParameterName(name);
      if (!commandLineNames.add(name)) {
        throw CliParser.usage("Duplicate --param name: " + name);
      }
      parameters.put(name, assignment.substring(equals + 1));
    }
  }

  private static void validateParameterName(String name) {
    if (name.isBlank()) {
      throw CliParser.usage("Parameter name cannot be blank");
    }
    String normalized = name.toLowerCase(Locale.ROOT);
    if (isReservedParameter(normalized)) {
      throw CliParser.usage("Reserved task parameter name: " + name);
    }
  }

  private static boolean isReservedParameter(String name) {
    return name.startsWith("nql.") || name.startsWith("jdbc.")
        || name.startsWith("cache.") || name.startsWith("nsql_")
        || name.startsWith("nql_");
  }

  private record InputSource(DataSourceSpec source, String filename) {
  }
}
