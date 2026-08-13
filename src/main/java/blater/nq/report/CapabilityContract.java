package blater.nq.report;

import blater.nq.Help;
import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The compiled, versioned contract exposed by {@code nq capabilities}. */
public final class CapabilityContract {
  public static final int CONTRACT_VERSION = 1;

  private CapabilityContract() {
  }

  public static Map<String, ?> details() {
    return map(
        "contract_version", CONTRACT_VERSION,
        "nq_version", Help.version(),
        "commands", commands(),
        "option_applicability", optionApplicability(),
        "option_aliases", optionAliases(),
        "invocation_aliases", invocationAliases(),
        "formats", formats(),
        "jdbc_drivers", jdbcDrivers(),
        "cli", cli(),
        "stdin", stdin(),
        "cache", cache(),
        "reports", reports(),
        "exit_codes", exitCodes());
  }

  private static List<?> commands() {
    return List.of(
        command("run", "nq [run] <script> [<data>] [options]",
            List.of("script", "data"), "optional-data", "result", "json"),
        command("convert", "nq convert [<data>] [options]",
            List.of("data"), "data", "result", "json"),
        command("catalog", "nq catalog [<data>] [<pattern>] [options]",
            List.of("data", "pattern"), "optional-data", "report", "markdown"),
        command("cache.load", "nq cache load [<data>] [<name>] [options]",
            List.of("data", "name"), "data", "report", "markdown"),
        command("cache.use", "nq cache use <name> [options]",
            List.of("name"), "ignored", "report", "markdown"),
        command("cache.list", "nq cache list [options]",
            List.of(), "ignored", "report", "markdown"),
        command("cache.clear", "nq cache clear (<name> | olderthan <age> | all) [options]",
            List.of("target"), "ignored", "report", "markdown"),
        command("capabilities", "nq capabilities [-r <format>]",
            List.of(), "ignored", "report", "json"),
        command("help", "nq help [<command> [<subcommand>]]",
            List.of("topic"), "ignored", "text", "text"),
        command("version", "nq version",
            List.of(), "ignored", "text", "text"));
  }

  private static Map<String, ?> command(
      String name,
      String usage,
      List<String> operands,
      String stdinRole,
      String outputKind,
      String defaultFormat) {
    return map(
        "name", name,
        "usage", usage,
        "positional_operands", operands,
        "stdin", stdinRole,
        "output_kind", outputKind,
        "default_format", defaultFormat);
  }

  private static Map<String, ?> optionApplicability() {
    List<String> input = List.of(
        "--input-file", "--input-text", "--input-format",
        "--params-file", "--param", "--parquet-root", "--parquet-record", "--debug");
    List<String> jdbc = List.of(
        "--db", "--database", "--host", "--port", "--user", "--password",
        "--jdbc-database", "--jdbc-driver", "--jdbc-class-name",
        "--jdbc-username", "--jdbc-password");
    List<String> run = concat(
        List.of("--script-file", "--script-text"), input,
        List.of("--output", "--cache", "--name", "--cache-dir", "--config",
            "--no-key-inference"), jdbc);
    List<String> catalog = concat(
        input, List.of("--pattern", "--report-format", "--cache", "--name",
            "--cache-dir", "--config"), jdbc);
    return map(
        "run", run,
        "convert", concat(input, List.of("--output")),
        "catalog", catalog,
        "cache.load", concat(input,
            List.of("--name", "--cache-dir", "--config", "--report-format")),
        "cache.use", List.of("--name", "--cache-dir", "--config", "--report-format", "--debug"),
        "cache.list", List.of("--cache-dir", "--config", "--report-format", "--debug"),
        "cache.clear", List.of(
            "--name", "--older-than", "--all", "--cache-dir", "--config",
            "--report-format", "--debug"),
        "capabilities", List.of("--report-format"),
        "help", List.of(),
        "version", List.of());
  }

  private static Map<String, ?> optionAliases() {
    return map(
        "--script-file", List.of("-f"),
        "--script-text", List.of("-e"),
        "--input-file", List.of("-i"),
        "--input-format", List.of("-t"),
        "--pattern", List.of("-m"),
        "--output", List.of("-o"),
        "--report-format", List.of("-r"));
  }

  private static Map<String, ?> invocationAliases() {
    return map(
        "capabilities", List.of("--capabilities"),
        "version", List.of("--version"));
  }

  private static Map<String, ?> formats() {
    List<Map<String, ?>> input = new ArrayList<>();
    for (InputType type : InputType.values()) {
      input.add(format(type.name(), inputAliases(type), inputExtensions(type)));
    }
    List<Map<String, ?>> result = new ArrayList<>();
    for (OutputType type : OutputType.values()) {
      result.add(format(type.name(), type == OutputType.MARKDOWN ? List.of("md") : List.of(), List.of()));
    }
    List<Map<String, ?>> report = new ArrayList<>();
    for (ReportFormat type : ReportFormat.values()) {
      report.add(format(type.name(), type == ReportFormat.MARKDOWN ? List.of("md") : List.of(), List.of()));
    }
    return map("input", input, "result", result, "report", report);
  }

  private static Map<String, ?> format(
      String name, List<String> aliases, List<String> extensions) {
    return map(
        "name", name.toLowerCase(Locale.ROOT),
        "aliases", aliases,
        "extensions", extensions);
  }

  private static List<String> inputAliases(InputType type) {
    return switch (type) {
      case JSONL -> List.of("json-lines", "ndjson");
      case YAML -> List.of("yml");
      default -> List.of();
    };
  }

  private static List<String> inputExtensions(InputType type) {
    return switch (type) {
      case XML -> List.of(".xml");
      case JSON -> List.of(".json");
      case JSONL -> List.of(".jsonl");
      case YAML -> List.of(".yaml", ".yml");
      case CSV -> List.of(".csv");
      case TSV -> List.of(".tsv");
      case TOML -> List.of(".toml");
      case PARQUET -> List.of(".parquet");
    };
  }

  private static List<?> jdbcDrivers() {
    return List.of(
        driver("h2", "h2db"),
        driver("postgresql", "postgres", "postgres-jdbc"),
        driver("mysql", "mysql-connector-j"),
        driver("mariadb"),
        driver("oracle", "ojdbc", "ojdbc11"),
        driver("sqlserver", "mssql", "mssql-jdbc", "sql-server"),
        driver("db2", "jcc"),
        driver("hana", "sap", "sap-hana", "ngdbc"),
        driver("informix"));
  }

  private static Map<String, ?> driver(String name, String... aliases) {
    return map("name", name, "aliases", List.of(aliases));
  }

  private static Map<String, ?> cli() {
    return map(
        "option_value_syntax", "--option value",
        "long_option_equals", false,
        "short_option_attached", false,
        "short_option_bundles", false,
        "end_of_options", "--",
        "option_names_case_sensitive", true,
        "commands_case_sensitive", false,
        "constant_values_case_sensitive", false,
        "help_modifier", "--help",
        "brief_help", "-h");
  }

  private static Map<String, ?> stdin() {
    return map(
        "role", "data-only",
        "bare_invocation", "brief-help",
        "default_format", "json",
        "explicit_operand", "-",
        "redirected_run_without_explicit_data", "consume",
        "terminal_run_without_explicit_data", "active-cache");
  }

  private static Map<String, ?> cache() {
    return map(
        "persistence", "explicit",
        "directory_option", "--cache-dir",
        "environment_variable", "NQ_CACHE_DIR",
        "generated_identity_column", "_nq_id",
        "reserved_column_prefix", "_nq_",
        "script_with_data_auto_loads", false,
        "load_activates", true);
  }

  private static Map<String, ?> reports() {
    return map(
        "schema_version", ReportEnvelope.SCHEMA_VERSION,
        "success_envelope_fields", List.of("schema_version", "status", "command", "details"),
        "diagnostic_envelope_fields", List.of("schema_version", "code", "level", "message", "usage"),
        "capabilities_default_format", "json");
  }

  private static Map<String, ?> exitCodes() {
    return map("success", 0, "execution_failure", 1, "usage_failure", 2, "interrupted", 130);
  }

  @SafeVarargs
  private static <T> List<T> concat(List<T>... lists) {
    List<T> combined = new ArrayList<>();
    for (List<T> list : lists) combined.addAll(list);
    return List.copyOf(combined);
  }

  private static Map<String, ?> map(Object... entries) {
    if (entries.length % 2 != 0) throw new IllegalArgumentException("Map entries must be pairs");
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < entries.length; index += 2) {
      result.put((String) entries[index], entries[index + 1]);
    }
    return Collections.unmodifiableMap(result);
  }
}
