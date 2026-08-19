package blater.nql.cli.parse;

import java.util.Set;

/** Enforces which parsed options belong to each CLI command and help topic. */
final class CliOptionValidator {
  private CliOptionValidator() {
  }

  static void requireNoOperands(CliParser.RawArguments raw, String command) {
    reject(!raw.positionals.isEmpty(), command + " accepts no operands");
  }

  static boolean hasNonHelpOptions(CliParser.RawArguments raw) {
    return raw.scriptFile != null || raw.scriptText != null || raw.inputFile != null
        || raw.inputText != null || raw.inputFormat != null || raw.pattern != null
        || raw.output != null || raw.reportFormat != null || raw.cache || raw.name != null
        || raw.cacheDirectory != null || raw.olderThan != null || raw.all
        || raw.config != null || raw.removedProperties != null || raw.paramsFile != null
        || !raw.params.isEmpty() || raw.parquetRoot != null || raw.parquetRecord != null
        || raw.debug || raw.noKeyInference || raw.capabilities || CliValueParser.hasJdbc(raw);
  }

  static boolean hasNonCapabilityOptions(CliParser.RawArguments raw) {
    return raw.scriptFile != null || raw.scriptText != null || raw.inputFile != null
        || raw.inputText != null || raw.inputFormat != null || raw.pattern != null
        || raw.output != null || raw.cache || raw.name != null
        || raw.cacheDirectoryExplicit || raw.olderThan != null || raw.all
        || raw.config != null || raw.removedProperties != null || raw.paramsFile != null
        || !raw.params.isEmpty() || raw.parquetRoot != null || raw.parquetRecord != null
        || raw.debug || raw.noKeyInference || CliValueParser.hasJdbc(raw);
  }

  static void rejectNonHelpOptions(CliParser.RawArguments raw, String command) {
    reject(hasNonHelpOptions(raw), command + " accepts no options");
  }

  static void validateHelpOptions(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    if (command == CliParser.Command.HELP) {
      rejectNonHelpOptions(raw, "help");
      CliHelpTopicValidator.validate(raw.positionals);
      return;
    }
    if (command == CliParser.Command.VERSION) {
      rejectNonHelpOptions(raw, "version");
      return;
    }
    validateOwnership(
        command == CliParser.Command.IMPLICIT ? CliImplicitBinder.implicitCommand(raw) : command,
        subcommand, raw);
    validateKnownOptionValues(raw);
  }

  private static void validateOwnership(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    switch (command) {
      case RUN -> validateRunOptionOwnership(raw);
      case CONVERT -> validateConvertOptionOwnership(raw);
      case CATALOG -> validateCatalogOptionOwnership(raw);
      case CACHE -> validateCacheOptionOwnership(subcommand, raw);
      case CAPABILITIES -> validateCapabilitiesOptionOwnership(raw);
      case HELP, VERSION, IMPLICIT -> throw new IllegalStateException("invalid help route");
    }
  }

  static void validateRunOptionOwnership(CliParser.RawArguments raw) {
    reject(raw.pattern != null, "--pattern is only valid for catalog");
    reject(raw.reportFormat != null, "--report-format is not valid for run");
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for run");
  }

  static void validateConvertOptionOwnership(CliParser.RawArguments raw) {
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for conversion");
    reject(raw.cache || raw.name != null, "cache options are not valid for conversion");
    reject(raw.pattern != null, "--pattern is not valid for conversion");
    reject(raw.reportFormat != null, "--report-format is not valid for conversion");
    reject(raw.cacheDirectoryExplicit, "--cache-dir is not valid for conversion");
    reject(raw.config != null, "--config is not valid for conversion");
    reject(CliValueParser.hasJdbc(raw), "database options are not valid for conversion");
    reject(raw.noKeyInference, "--no-key-inference is not valid for conversion");
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for conversion");
  }

  static void validateCatalogOptionOwnership(CliParser.RawArguments raw) {
    reject(raw.output != null, "--output is not valid for catalog");
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for catalog");
    reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for catalog");
  }

  static void validateCacheOptionOwnership(String subcommand, CliParser.RawArguments raw) {
    reject(raw.cache, "--cache is not valid inside a cache subcommand");
    reject(raw.output != null, "cache commands use --report-format, not --output");
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for cache commands");
    reject(raw.pattern != null, "--pattern is only valid for catalog");
    reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    reject(CliValueParser.hasJdbc(raw), "database options are not valid for cache commands");
    if (subcommand != null && !subcommand.equals("load")) {
      rejectNonLoadCacheOptions(subcommand, raw);
    }
  }

  private static void rejectNonLoadCacheOptions(
      String subcommand, CliParser.RawArguments raw) {
    reject(raw.inputFile != null || raw.inputText != null || raw.inputFormat != null,
        "input options are not valid for cache " + subcommand);
    reject(raw.paramsFile != null || !raw.params.isEmpty(),
        "parameters are not valid for cache " + subcommand);
    reject(raw.parquetRoot != null || raw.parquetRecord != null,
        "Parquet options are not valid for cache " + subcommand);
  }

  static void validateCapabilitiesOptionOwnership(CliParser.RawArguments raw) {
    reject(hasNonCapabilityOptions(raw), "capabilities accepts only --report-format");
  }

  private static void validateKnownOptionValues(CliParser.RawArguments raw) {
    if (raw.inputFormat != null) CliValueParser.inputType(raw.inputFormat);
    if (raw.output != null) CliValueParser.outputType(raw.output);
    if (raw.reportFormat != null) CliValueParser.reportFormat(raw.reportFormat);
    if (raw.databaseType != null) CliValueParser.knownDriver(raw.databaseType);
    if (raw.jdbcDriver != null) CliValueParser.knownDriver(raw.jdbcDriver);
    if (raw.olderThan != null) CliValueParser.age(raw.olderThan);
    if (raw.name != null) CliValueParser.cacheName(raw.name);
  }

  private static void reject(boolean condition, String message) {
    if (condition) {
      throw usage(message);
    }
  }

  private static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }
}
