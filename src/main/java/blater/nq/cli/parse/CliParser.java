package blater.nq.cli.parse;

import blater.nq.cli.CacheInvocation;
import blater.nq.cli.CacheName;
import blater.nq.cli.CacheNameSelection;
import blater.nq.cli.CatalogInvocation;
import blater.nq.cli.CatalogPattern;
import blater.nq.cli.ConvertInvocation;
import blater.nq.cli.Credentials;
import blater.nq.cli.DataInput;
import blater.nq.cli.DataSourceSpec;
import blater.nq.cli.DriverSelection;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.HelpInvocation;
import blater.nq.cli.InputSelection;
import blater.nq.cli.InvocationOptions;
import blater.nq.cli.JdbcConnectionSpec;
import blater.nq.cli.NqInvocation;
import blater.nq.cli.OutputSelection;
import blater.nq.cli.ParquetOverrides;
import blater.nq.cli.RunInvocation;
import blater.nq.cli.ScriptSource;
import blater.nq.cli.VersionInvocation;
import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import blater.nq.report.ReportFormat;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parses the agent-friendly CLI into an immutable typed invocation. */
public final class CliParser {
  private static final Set<String> VALUE_OPTIONS = Set.of(
      "-f", "--script-file", "-e", "--script-text",
      "-i", "--input-file", "--input-text", "-t", "--input-format",
      "-m", "--pattern", "-o", "--output", "-r", "--report-format",
      "--name", "--older-than", "--cache-dir", "--config",
      "-p", "--properties", "--params-file", "--param", "--parquet-root", "--parquet-record",
      "--db", "--database", "--host", "--port", "--user", "--password",
      "--jdbc-username", "--jdbc-password", "--jdbc-database",
      "--jdbc-driver", "--jdbc-class-name");
  private static final Map<String, String> BOOLEAN_OPTIONS = Map.of(
      "--cache", "cache",
      "--all", "all",
      "--debug", "debug",
      "--no-key-inference", "no-key-inference",
      "-h", "help",
      "--help", "help",
      "--version", "version");

  private final Map<String, String> environment;
  private final Path userHome;

  public CliParser() {
    this(System.getenv(), Path.of(System.getProperty("user.home")));
  }

  /** Injectable process settings for hermetic parser tests and embedding. */
  public CliParser(Map<String, String> environment, Path userHome) {
    this.environment = Map.copyOf(environment);
    this.userHome = userHome.toAbsolutePath().normalize();
  }

  public NqInvocation parse(String... args) {
    String[] safeArgs = args == null ? new String[0] : args.clone();
    validateLexicalSyntax(safeArgs);
    Route route = route(safeArgs);
    RawArguments raw = new RawArguments();
    CommandLine commandLine = configured(raw);
    try {
      commandLine.parseArgs(route.remaining().toArray(String[]::new));
    } catch (CommandLine.ParameterException ex) {
      throw new CliUsageException(ex.getMessage(), ex);
    }
    if (raw.removedProperties != null) {
      throw usage("-p/--properties was removed; use --config for NQ settings "
          + "or --params-file for task parameters");
    }
    if (!raw.help && !raw.briefHelp && !raw.version) {
      applyConfiguration(route.command(), raw);
    }
    return bind(route.command(), route.subcommand(), raw);
  }

  private CommandLine configured(RawArguments raw) {
    return new CommandLine(raw)
        .setSeparator(" ")
        .setPosixClusteredShortOptionsAllowed(false)
        .setOverwrittenOptionsAllowed(false)
        .setToggleBooleanFlags(false)
        .setExpandAtFiles(false)
        .setAbbreviatedOptionsAllowed(false)
        .setAbbreviatedSubcommandsAllowed(false)
        .setUnmatchedOptionsArePositionalParams(false)
        .setOptionsCaseInsensitive(false)
        .setCaseInsensitiveEnumValuesAllowed(true);
  }

  private Route route(String[] args) {
    List<String> remaining = new ArrayList<>(List.of(args));
    int first = firstPositionalBeforeDelimiter(remaining, 0);
    if (first < 0) {
      return new Route(Command.IMPLICIT, null, remaining);
    }
    Command command = Command.from(remaining.get(first));
    if (command == null) {
      return new Route(Command.IMPLICIT, null, remaining);
    }
    remaining.remove(first);

    String subcommand = null;
    if (command == Command.CACHE) {
      int subcommandIndex = firstPositionalBeforeDelimiter(remaining, 0);
      if (subcommandIndex < 0 && containsHelpFlag(remaining)) {
        return new Route(command, null, remaining);
      }
      if (subcommandIndex < 0) {
        throw usage("cache requires a subcommand: load, use, list, or clear");
      }
      subcommand = remaining.remove(subcommandIndex).toLowerCase(Locale.ROOT);
      if (!Set.of("load", "use", "list", "clear").contains(subcommand)) {
        throw usage("Unknown cache subcommand: " + subcommand);
      }
    }
    return new Route(command, subcommand, remaining);
  }

  private int firstPositionalBeforeDelimiter(List<String> args, int start) {
    for (int index = start; index < args.size(); index++) {
      String token = args.get(index);
      if ("--".equals(token)) {
        return -1;
      }
      if (VALUE_OPTIONS.contains(token)) {
        index++;
        continue;
      }
      if (token.startsWith("-")) {
        continue;
      }
      return index;
    }
    return -1;
  }

  private NqInvocation bind(Command command, String subcommand, RawArguments raw) {
    if (raw.version) {
      if (command != Command.IMPLICIT || !raw.positionals.isEmpty()
          || raw.help || raw.briefHelp || hasNonHelpOptions(raw)) {
        throw usage("--version is only valid as a root invocation");
      }
      return new VersionInvocation();
    }
    if (command == Command.HELP || raw.help || raw.briefHelp) {
      return bindHelp(command, subcommand, raw);
    }
    if (command == Command.VERSION) {
      requireNoOperands(raw, "version");
      rejectNonHelpOptions(raw, "version");
      return new VersionInvocation();
    }
    return switch (command) {
      case RUN -> bindRun(raw);
      case CONVERT -> bindConvert(raw);
      case CATALOG -> bindCatalog(raw);
      case CACHE -> bindCache(subcommand, raw);
      case IMPLICIT -> bindImplicit(raw);
      case HELP, VERSION -> throw new IllegalStateException("handled above");
    };
  }

  private NqInvocation bindImplicit(RawArguments raw) {
    boolean hasNamedScript = raw.scriptFile != null || raw.scriptText != null;
    boolean hasNamedInput = raw.inputFile != null || raw.inputText != null;
    boolean positionalScript = raw.positionals.stream().anyMatch(CliParser::isScriptFilename);
    boolean positionalData = raw.positionals.stream().anyMatch(CliParser::isDataFilename);

    // A positional operand paired with a named input fills the remaining script
    // slot. This is the same typed-slot rule as --script-file plus positional data.
    if (hasNamedScript || positionalScript || raw.positionals.size() > 1
        || (hasNamedInput && !raw.positionals.isEmpty())
        || (!hasNamedInput && !positionalData && !raw.positionals.isEmpty())) {
      return bindRun(raw);
    }
    if (raw.cache) {
      return bindImplicitCacheLoad(raw);
    }
    return bindConvert(raw);
  }

  private void applyConfiguration(Command command, RawArguments raw) {
    Command effective = command == Command.IMPLICIT ? implicitCommand(raw) : command;
    if (raw.config != null
        && (effective == Command.CONVERT || effective == Command.HELP || effective == Command.VERSION)) {
      throw usage("--config is only valid for run, catalog, and cache commands");
    }

    Map<String, String> config = raw.config == null
        ? Map.of()
        : operationalConfig(Path.of(raw.config));
    String environmentCacheDirectory = environment.get("NQ_CACHE_DIR");
    if (raw.cacheDirectory == null) {
      if (environmentCacheDirectory != null && !environmentCacheDirectory.isBlank()) {
        raw.cacheDirectory = environmentCacheDirectory;
      } else {
        raw.cacheDirectory = config.get("cache.dir");
      }
    }

    if (effective == Command.CACHE || raw.cache) {
      return;
    }
    boolean cliSimpleIdentity = raw.databaseType != null || raw.database != null
        || raw.host != null || raw.port != null;
    boolean cliExactIdentity = raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
    reject(cliSimpleIdentity && cliExactIdentity,
        "simple database options cannot be combined with exact JDBC options");
    if (!cliSimpleIdentity) {
      if (raw.jdbcDatabase == null) raw.jdbcDatabase = config.get("jdbc.database");
      if (raw.jdbcDriver == null && raw.jdbcClassName == null) {
        raw.jdbcDriver = config.get("jdbc.driver");
        raw.jdbcClassName = config.get("jdbc.class.name");
      }
    }
    boolean effectiveJdbcIdentity = cliSimpleIdentity || raw.jdbcDatabase != null
        || raw.jdbcDriver != null || raw.jdbcClassName != null;
    if (effectiveJdbcIdentity) {
      if (raw.user == null && raw.jdbcUsername == null) {
        raw.jdbcUsername = config.get("jdbc.username");
      }
      if (raw.password == null && raw.jdbcPassword == null) {
        raw.jdbcPassword = config.get("jdbc.password");
      }
    }
  }

  private static Command implicitCommand(RawArguments raw) {
    boolean hasNamedScript = raw.scriptFile != null || raw.scriptText != null;
    boolean hasNamedInput = raw.inputFile != null || raw.inputText != null;
    boolean positionalScript = raw.positionals.stream().anyMatch(CliParser::isScriptFilename);
    boolean positionalData = raw.positionals.stream().anyMatch(CliParser::isDataFilename);
    if (hasNamedScript || positionalScript || raw.positionals.size() > 1
        || (hasNamedInput && !raw.positionals.isEmpty())
        || (!hasNamedInput && !positionalData && !raw.positionals.isEmpty())) {
      return Command.RUN;
    }
    return raw.cache ? Command.CACHE : Command.CONVERT;
  }

  private static Map<String, String> operationalConfig(Path path) {
    Map<String, String> values = CliPropertyFiles.read(path, "config");
    Set<String> supported = Set.of(
        "cache.dir", "jdbc.database", "jdbc.driver", "jdbc.class.name",
        "jdbc.username", "jdbc.password");
    for (String key : values.keySet()) {
      if (!supported.contains(key)) {
        throw usage("Unsupported config key: " + key);
      }
    }
    reject(values.containsKey("jdbc.driver") && values.containsKey("jdbc.class.name"),
        "config keys jdbc.driver and jdbc.class.name are mutually exclusive");
    return values;
  }

  private RunInvocation bindRun(RawArguments raw) {
    validateRunOptionOwnership(raw);
    reject(raw.name != null && !raw.cache, "--name requires --cache");
    reject(raw.cache && hasJdbc(raw), "run cannot combine --cache and JDBC");
    reject(raw.scriptFile != null && raw.scriptText != null,
        "run accepts exactly one script source");
    reject(raw.inputFile != null && raw.inputText != null,
        "run accepts at most one data source");

    Slots slots = bindScriptAndData(raw.positionals, raw.scriptFile != null || raw.scriptText != null,
        raw.inputFile != null || raw.inputText != null, "run");
    ScriptSource script = scriptSource(raw, slots.script());
    if (script == null) {
      throw usage("run requires a script");
    }
    DataInput data = dataInput(raw, slots.data(), false);
    validateParquetOptions(raw, data == null ? implicitInputType(raw) : data.format());
    ExecutionTarget target;
    if (raw.cache) {
      target = raw.name == null
          ? new ExecutionTarget.ActiveCache(cacheDirectory(raw))
          : new ExecutionTarget.NamedCache(cacheDirectory(raw), cacheName(raw.name));
    } else if (hasJdbc(raw)) {
      target = new ExecutionTarget.Jdbc(jdbcConnection(raw));
    } else if (data != null) {
      reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for temporary data execution");
      target = new ExecutionTarget.Temporary();
    } else {
      target = new ExecutionTarget.InputOrActiveCache(cacheDirectory(raw));
    }
    if (target instanceof ExecutionTarget.Jdbc) {
      reject(raw.cacheDirectoryExplicit, "--cache-dir is not valid for JDBC execution");
    }
    return new RunInvocation(
        script,
        data == null
            ? new InputSelection.Automatic(implicitInputType(raw))
            : new InputSelection.Provided(data),
        target,
        raw.output == null
            ? new OutputSelection.ScriptOrDefault()
            : new OutputSelection.Explicit(outputType(raw.output)),
        raw.noKeyInference,
        invocationOptions(raw));
  }

  private ConvertInvocation bindConvert(RawArguments raw) {
    validateConvertOptionOwnership(raw);
    reject(raw.inputFile != null && raw.inputText != null,
        "conversion accepts exactly one data source");
    if (raw.positionals.size() > 1) {
      throw usage("conversion accepts exactly one data source");
    }
    reject((raw.inputFile != null || raw.inputText != null) && !raw.positionals.isEmpty(),
        "positional data conflicts with its named input option");
    String positional = raw.positionals.isEmpty() ? null : raw.positionals.getFirst();
    DataInput input = dataInput(raw, positional, true);
    validateParquetOptions(raw, input.format());
    return new ConvertInvocation(
        input,
        raw.output == null ? OutputType.JSON : outputType(raw.output),
        invocationOptions(raw));
  }

  private CatalogInvocation bindCatalog(RawArguments raw) {
    validateCatalogOptionOwnership(raw);
    reject(raw.name != null && !raw.cache, "--name requires --cache");
    reject(raw.cache && hasJdbc(raw), "catalog cannot combine --cache and JDBC");
    reject(raw.inputFile != null && raw.inputText != null,
        "catalog accepts at most one data source");

    String data = null;
    String pattern = raw.pattern;
    List<String> unknown = new ArrayList<>();
    for (String positional : raw.positionals) {
      if (isDataFilename(positional)) {
        if (data != null || raw.inputFile != null || raw.inputText != null) {
          throw usage("catalog accepts one data source");
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
        throw usage("catalog accepts at most a data source and pattern");
      }
    }
    if (!unknown.isEmpty()) {
      reject(pattern != null, "positional pattern conflicts with --pattern");
      pattern = unknown.getFirst();
    }
    DataInput input = dataInput(raw, data, false);
    validateParquetOptions(raw, input == null ? implicitInputType(raw) : input.format());
    if (input == null) {
      reject(raw.paramsFile != null || !raw.params.isEmpty(),
          "task parameters require catalog input data");
    }
    ExecutionTarget target;
    if (input != null) {
      reject(raw.cache || hasJdbc(raw), "catalog data conflicts with another source");
      reject(raw.cacheDirectoryExplicit,
          "--cache-dir is not valid for temporary catalog execution");
      target = new ExecutionTarget.Temporary();
    } else if (raw.cache) {
      target = raw.name == null
          ? new ExecutionTarget.ActiveCache(cacheDirectory(raw))
          : new ExecutionTarget.NamedCache(cacheDirectory(raw), cacheName(raw.name));
    } else if (hasJdbc(raw)) {
      target = new ExecutionTarget.Jdbc(jdbcConnection(raw));
    } else {
      target = new ExecutionTarget.InputOrActiveCache(cacheDirectory(raw));
    }
    if (target instanceof ExecutionTarget.Jdbc) {
      reject(raw.cacheDirectoryExplicit, "--cache-dir is not valid for JDBC catalog execution");
    }
    return new CatalogInvocation(
        input == null
            ? new InputSelection.Automatic(implicitInputType(raw))
            : new InputSelection.Provided(input),
        pattern == null ? new CatalogPattern.All() : new CatalogPattern.Matching(pattern),
        target,
        raw.reportFormat == null ? ReportFormat.MARKDOWN : reportFormat(raw.reportFormat),
        invocationOptions(raw));
  }

  private CacheInvocation bindCache(String subcommand, RawArguments raw) {
    validateCacheOptionOwnership(subcommand, raw);
    ReportFormat format = raw.reportFormat == null ? ReportFormat.MARKDOWN : reportFormat(raw.reportFormat);
    return switch (subcommand) {
      case "load" -> bindCacheLoad(raw, format);
      case "use" -> bindCacheUse(raw, format);
      case "list" -> bindCacheList(raw, format);
      case "clear" -> bindCacheClear(raw, format);
      default -> throw usage("Unknown cache subcommand: " + subcommand);
    };
  }

  private CacheInvocation.Load bindCacheLoad(RawArguments raw, ReportFormat format) {
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for cache load");
    reject(raw.inputFile != null && raw.inputText != null, "cache load accepts one data source");
    String data = null;
    String name = raw.name;
    List<String> unknown = new ArrayList<>();
    for (String positional : raw.positionals) {
      if (isDataFilename(positional)) {
        reject(data != null || raw.inputFile != null || raw.inputText != null,
            "cache load accepts one data source");
        data = positional;
      } else {
        unknown.add(positional);
      }
    }
    if (!unknown.isEmpty()) {
      if (data == null && raw.inputFile == null && raw.inputText == null && unknown.size() > 1) {
        data = unknown.removeFirst();
      }
      if (!unknown.isEmpty()) {
        reject(name != null, "positional cache name conflicts with --name");
        name = unknown.removeFirst();
      }
      reject(!unknown.isEmpty(), "cache load accepts at most data and cache name");
    }
    DataInput input = dataInput(raw, data, true);
    validateParquetOptions(raw, input.format());
    return new CacheInvocation.Load(
        input,
        name == null
            ? new CacheNameSelection.Generated()
            : new CacheNameSelection.Named(cacheName(name)),
        cacheDirectory(raw), format, invocationOptions(raw));
  }

  private CacheInvocation.Use bindCacheUse(RawArguments raw, ReportFormat format) {
    rejectDataOptions(raw, "cache use");
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for cache use");
    String name = singleName(raw, "cache use");
    return new CacheInvocation.Use(cacheName(name), cacheDirectory(raw), format, raw.debug);
  }

  private CacheInvocation.ListCaches bindCacheList(RawArguments raw, ReportFormat format) {
    rejectDataOptions(raw, "cache list");
    reject(raw.name != null || raw.all || raw.olderThan != null || !raw.positionals.isEmpty(),
        "cache list accepts no target");
    return new CacheInvocation.ListCaches(cacheDirectory(raw), format, raw.debug);
  }

  private CacheInvocation.Clear bindCacheClear(RawArguments raw, ReportFormat format) {
    rejectDataOptions(raw, "cache clear");
    int namedModes = (raw.name == null ? 0 : 1) + (raw.all ? 1 : 0) + (raw.olderThan == null ? 0 : 1);
    reject(namedModes > 1, "cache clear accepts exactly one target");
    CacheInvocation.ClearTarget target;
    if (raw.all) {
      reject(!raw.positionals.isEmpty(), "cache clear --all accepts no positional target");
      target = new CacheInvocation.ClearTarget.All();
    } else if (raw.olderThan != null) {
      reject(!raw.positionals.isEmpty(), "cache clear --older-than accepts no positional target");
      target = new CacheInvocation.ClearTarget.OlderThan(age(raw.olderThan));
    } else if (raw.name != null) {
      reject(!raw.positionals.isEmpty(), "positional cache name conflicts with --name");
      target = new CacheInvocation.ClearTarget.Name(cacheName(raw.name));
    } else {
      if (raw.positionals.isEmpty()) {
        throw usage("cache clear requires a cache name, olderthan <age>, or all");
      }
      if (raw.positionals.size() == 1 && equalsWord(raw.positionals.getFirst(), "all")) {
        target = new CacheInvocation.ClearTarget.All();
      } else if (raw.positionals.size() == 2 && equalsWord(raw.positionals.getFirst(), "olderthan")) {
        target = new CacheInvocation.ClearTarget.OlderThan(age(raw.positionals.get(1)));
      } else if (raw.positionals.size() == 1) {
        target = new CacheInvocation.ClearTarget.Name(cacheName(raw.positionals.getFirst()));
      } else {
        throw usage("cache clear requires a cache name, olderthan <age>, or all");
      }
    }
    return new CacheInvocation.Clear(target, cacheDirectory(raw), format, raw.debug);
  }

  private CacheInvocation.Load bindImplicitCacheLoad(RawArguments raw) {
    reject(raw.pattern != null, "--pattern is only valid for catalog");
    reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for cache load");
    reject(hasJdbc(raw), "database options are not valid for cache load");
    reject(raw.output != null, "cache loading uses --report-format, not --output");
    if (raw.positionals.size() > 1) {
      throw usage("implicit cache loading accepts one data source");
    }
    String positional = raw.positionals.isEmpty() ? null : raw.positionals.getFirst();
    return new CacheInvocation.Load(
        dataInput(raw, positional, true),
        raw.name == null
            ? new CacheNameSelection.Generated()
            : new CacheNameSelection.Named(cacheName(raw.name)),
        cacheDirectory(raw),
        raw.reportFormat == null ? ReportFormat.MARKDOWN : reportFormat(raw.reportFormat),
        invocationOptions(raw));
  }

  private HelpInvocation bindHelp(Command command, String subcommand, RawArguments raw) {
    validateHelpOptions(command, subcommand, raw);
    if (command == Command.HELP) {
      List<String> topic = raw.positionals.stream()
          .map(value -> value.toLowerCase(Locale.ROOT)).toList();
      if (topic.size() > 2) {
        throw usage("help accepts a command and optional subcommand");
      }
      return new HelpInvocation(topic, false);
    }
    if (raw.briefHelp) {
      return new HelpInvocation(List.of(), true);
    }
    List<String> topic = switch (command) {
      case RUN -> List.of("run");
      case CONVERT -> List.of("convert");
      case CATALOG -> List.of("catalog");
      case CACHE -> subcommand == null ? List.of("cache") : List.of("cache", subcommand);
      case VERSION -> List.of("version");
      case IMPLICIT -> implicitHelpTopic(raw);
      case HELP -> List.of();
    };
    return new HelpInvocation(topic, false);
  }

  private List<String> implicitHelpTopic(RawArguments raw) {
    if (raw.inputFile != null || raw.inputText != null
        || (raw.positionals.size() == 1 && isDataFilename(raw.positionals.getFirst()))) {
      return List.of("convert");
    }
    if (raw.scriptFile != null || raw.scriptText != null
        || raw.positionals.stream().anyMatch(CliParser::isScriptFilename)) {
      return List.of("run");
    }
    return List.of();
  }

  private Slots bindScriptAndData(
      List<String> positionals, boolean namedScript, boolean namedData, String command) {
    String script = null;
    String data = null;
    List<String> unknown = new ArrayList<>();
    for (String positional : positionals) {
      if (isScriptFilename(positional)) {
        reject(script != null || namedScript, "positional script conflicts with another script source");
        script = positional;
      } else if (isDataFilename(positional) || "-".equals(positional)) {
        reject(data != null || namedData, "positional data conflicts with another data source");
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
        throw usage(command + " has too many positional operands");
      }
    }
    return new Slots(script, data);
  }

  private ScriptSource scriptSource(RawArguments raw, String positional) {
    if (raw.scriptFile != null) return new ScriptSource.File(Path.of(raw.scriptFile));
    if (raw.scriptText != null) return new ScriptSource.Text(raw.scriptText);
    if (positional == null) return null;
    return isScriptFilename(positional)
        ? new ScriptSource.File(Path.of(positional))
        : new ScriptSource.Text(positional);
  }

  private DataInput dataInput(RawArguments raw, String positional, boolean defaultStdin) {
    DataSourceSpec source;
    String filename = null;
    if (raw.inputFile != null) {
      filename = raw.inputFile;
      source = "-".equals(filename)
          ? new DataSourceSpec.StandardInput()
          : new DataSourceSpec.File(Path.of(filename));
    } else if (raw.inputText != null) {
      source = new DataSourceSpec.Text(raw.inputText);
    } else if (positional != null) {
      filename = positional;
      if ("-".equals(positional)) source = new DataSourceSpec.StandardInput();
      else if (isDataFilename(positional)) source = new DataSourceSpec.File(Path.of(positional));
      else source = new DataSourceSpec.Text(positional);
    } else if (defaultStdin) {
      source = new DataSourceSpec.StandardInput();
    } else {
      return null;
    }
    InputType type = raw.inputFormat == null
        ? (filename != null && isDataFilename(filename) ? InputType.fromFilename(filename) : InputType.JSON)
        : inputType(raw.inputFormat);
    return new DataInput(source, type);
  }

  private static InputType implicitInputType(RawArguments raw) {
    return raw.inputFormat == null ? InputType.JSON : inputType(raw.inputFormat);
  }

  private static void validateParquetOptions(RawArguments raw, InputType inputType) {
    if ((raw.parquetRoot != null || raw.parquetRecord != null) && inputType != InputType.PARQUET) {
      throw usage("--parquet-root and --parquet-record require Parquet input");
    }
  }

  private InvocationOptions invocationOptions(RawArguments raw) {
    ParquetOverrides.Value root = raw.parquetRoot == null
        ? new ParquetOverrides.Value.Inferred()
        : new ParquetOverrides.Value.Explicit(raw.parquetRoot);
    ParquetOverrides.Value record = raw.parquetRecord == null
        ? new ParquetOverrides.Value.Inferred()
        : new ParquetOverrides.Value.Explicit(raw.parquetRecord);
    return new InvocationOptions(taskParameters(raw), raw.debug, new ParquetOverrides(root, record));
  }

  private static Map<String, String> taskParameters(RawArguments raw) {
    Map<String, String> parameters = new LinkedHashMap<>();
    if (raw.paramsFile != null) {
      CliPropertyFiles.read(Path.of(raw.paramsFile), "parameters").forEach((name, value) -> {
        validateParameterName(name);
        parameters.put(name, value);
      });
    }
    Set<String> cliNames = new HashSet<>();
    for (String assignment : raw.params) {
      int equals = assignment.indexOf('=');
      if (equals <= 0) {
        throw usage("--param requires a non-empty name=value assignment: " + assignment);
      }
      String name = assignment.substring(0, equals);
      validateParameterName(name);
      if (!cliNames.add(name)) {
        throw usage("Duplicate --param name: " + name);
      }
      parameters.put(name, assignment.substring(equals + 1));
    }
    return Map.copyOf(parameters);
  }

  private static void validateParameterName(String name) {
    if (name.isBlank()) throw usage("Parameter name cannot be blank");
    String normalized = name.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("nq.") || normalized.startsWith("jdbc.")
        || normalized.startsWith("cache.") || normalized.startsWith("nsql_")
        || normalized.startsWith("nq_")) {
      throw usage("Reserved task parameter name: " + name);
    }
  }

  private Path cacheDirectory(RawArguments raw) {
    String configured = raw.cacheDirectory;
    if (configured == null || configured.isBlank()) {
      configured = userHome.resolve(".nq").resolve("cache").toString();
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  private String singleName(RawArguments raw, String command) {
    reject(raw.name != null && !raw.positionals.isEmpty(),
        "positional cache name conflicts with --name");
    if (raw.positionals.size() > 1) throw usage(command + " accepts one cache name");
    String name = raw.name != null ? raw.name
        : (raw.positionals.isEmpty() ? null : raw.positionals.getFirst());
    if (name == null) throw usage(command + " requires a cache name");
    return name;
  }

  private void rejectDataOptions(RawArguments raw, String command) {
    reject(raw.inputFile != null || raw.inputText != null || raw.inputFormat != null,
        "input options are not valid for " + command);
    reject(raw.paramsFile != null || !raw.params.isEmpty(),
        "parameters are not valid for " + command);
    reject(raw.parquetRoot != null || raw.parquetRecord != null,
        "Parquet options are not valid for " + command);
  }

  private static InputType inputType(String value) {
    if (value == null || value.isBlank()) throw usage("No input format supplied");
    String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    return switch (normalized) {
      case "xml" -> InputType.XML;
      case "json" -> InputType.JSON;
      case "jsonl", "json-lines", "ndjson" -> InputType.JSONL;
      case "yaml", "yml" -> InputType.YAML;
      case "csv" -> InputType.CSV;
      case "tsv" -> InputType.TSV;
      case "toml" -> InputType.TOML;
      case "parquet" -> InputType.PARQUET;
      default -> throw usage("Unsupported input format: " + value);
    };
  }

  private static OutputType outputType(String value) {
    if (equalsWord(value, "md")) return OutputType.MARKDOWN;
    try {
      return OutputType.fromName(value);
    } catch (RuntimeException ex) {
      throw new CliUsageException(ex.getMessage(), ex);
    }
  }

  private static ReportFormat reportFormat(String value) {
    try {
      return ReportFormat.fromName(value);
    } catch (RuntimeException ex) {
      throw new CliUsageException(ex.getMessage(), ex);
    }
  }

  private static CacheName cacheName(String value) {
    try {
      return new CacheName(value);
    } catch (IllegalArgumentException ex) {
      throw new CliUsageException(ex.getMessage(), ex);
    }
  }

  private static Duration age(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int split = 0;
    while (split < normalized.length() && Character.isDigit(normalized.charAt(split))) split++;
    if (split == 0) throw usage("Invalid cache age: " + value);
    long amount;
    try {
      amount = Long.parseLong(normalized.substring(0, split));
    } catch (NumberFormatException ex) {
      throw new CliUsageException("Invalid cache age: " + value, ex);
    }
    String unit = normalized.substring(split).trim();
    try {
      return switch (unit) {
        case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
        case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
        case "d", "day", "days" -> Duration.ofDays(amount);
        default -> throw usage("Invalid cache age: " + value);
      };
    } catch (ArithmeticException ex) {
      throw new CliUsageException("Cache age is too large: " + value, ex);
    }
  }

  private static boolean isScriptFilename(String value) {
    return value.toLowerCase(Locale.ROOT).endsWith(".nq");
  }

  private static boolean isDataFilename(String value) {
    return InputType.supportsFilename(value);
  }

  private static boolean equalsWord(String left, String right) {
    return left.equalsIgnoreCase(right);
  }

  private static boolean hasJdbc(RawArguments raw) {
    return raw.databaseType != null || raw.database != null || raw.host != null || raw.port != null
        || raw.user != null || raw.password != null || raw.jdbcUsername != null
        || raw.jdbcPassword != null || raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
  }

  private static JdbcConnectionSpec jdbcConnection(RawArguments raw) {
    boolean simpleIdentity = raw.databaseType != null || raw.database != null
        || raw.host != null || raw.port != null;
    boolean exactIdentity = raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
    reject(simpleIdentity && exactIdentity,
        "simple database options cannot be combined with exact JDBC options");
    reject(raw.user != null && raw.jdbcUsername != null,
        "--user and --jdbc-username are aliases and cannot be combined");
    reject(raw.password != null && raw.jdbcPassword != null,
        "--password and --jdbc-password are aliases and cannot be combined");
    reject(raw.jdbcDriver != null && raw.jdbcClassName != null,
        "--jdbc-driver and --jdbc-class-name are mutually exclusive");

    String username = raw.user != null ? raw.user : raw.jdbcUsername;
    String password = raw.password != null ? raw.password : raw.jdbcPassword;
    if (simpleIdentity) {
      reject((raw.databaseType == null) != (raw.database == null),
          "--db and --database must be supplied together");
      reject((raw.host != null || raw.port != null) && raw.databaseType == null,
          "--host and --port require --db");
      String driver = knownDriver(raw.databaseType);
      reject(driver.equals("h2") && (raw.host != null || raw.port != null),
          "--host and --port are not valid for H2");
      reject((driver.equals("hana") || driver.equals("informix")) && raw.port == null,
          "--port is required for " + driver);
      if (username == null) username = defaultUsername(driver);
      return new JdbcConnectionSpec(
          jdbcUrl(driver, raw.database, raw.host, raw.port),
          new DriverSelection.Known(driver),
          credentials(username, password));
    }

    reject(!exactIdentity,
        "database credentials require --jdbc-database or the --db/--database form");
    reject(raw.jdbcDatabase == null,
        "--jdbc-database is required with an exact JDBC driver hint");
    DriverSelection driver = raw.jdbcClassName != null
        ? new DriverSelection.ClassName(raw.jdbcClassName)
        : raw.jdbcDriver != null
            ? new DriverSelection.Known(knownDriver(raw.jdbcDriver))
            : new DriverSelection.Automatic();
    return new JdbcConnectionSpec(raw.jdbcDatabase, driver, credentials(username, password));
  }

  private static Credentials credentials(String username, String password) {
    return new Credentials(
        username == null
            ? new Credentials.Value.Unspecified()
            : new Credentials.Value.Specified(username),
        password == null
            ? new Credentials.Value.Unspecified()
            : new Credentials.Value.Specified(password));
  }

  private static String knownDriver(String value) {
    if (value == null || value.isBlank()) throw usage("No database type supplied");
    String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    return switch (normalized) {
      case "h2", "h2db" -> "h2";
      case "postgresql", "postgres", "postgres-jdbc" -> "postgresql";
      case "mysql", "mysql-connector-j" -> "mysql";
      case "mariadb" -> "mariadb";
      case "oracle", "ojdbc", "ojdbc11" -> "oracle";
      case "sqlserver", "mssql", "mssql-jdbc", "sql-server" -> "sqlserver";
      case "db2", "jcc" -> "db2";
      case "hana", "sap", "sap-hana", "ngdbc" -> "hana";
      case "informix" -> "informix";
      default -> throw usage("Unsupported JDBC driver: " + value);
    };
  }

  private static String defaultUsername(String driver) {
    return switch (driver) {
      case "h2" -> "sa";
      case "postgresql", "mysql", "mariadb" -> System.getProperty("user.name");
      default -> null;
    };
  }

  private static String defaultPort(String driver) {
    return switch (driver) {
      case "postgresql" -> "5432";
      case "mysql", "mariadb" -> "3306";
      case "oracle" -> "1521";
      case "sqlserver" -> "1433";
      case "db2" -> "50000";
      default -> null;
    };
  }

  private static String jdbcUrl(String driver, String database, String host, String port) {
    String resolvedHost = host == null ? "localhost" : host;
    String resolvedPort = port == null ? defaultPort(driver) : port;
    return switch (driver) {
      case "h2" -> "jdbc:h2:" + database;
      case "postgresql" -> "jdbc:postgresql://" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "mysql" -> "jdbc:mysql://" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "mariadb" -> "jdbc:mariadb://" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "oracle" -> "jdbc:oracle:thin:@//" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "sqlserver" -> "jdbc:sqlserver://" + resolvedHost + ":" + resolvedPort
          + ";databaseName=" + database;
      case "db2" -> "jdbc:db2://" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "hana" -> "jdbc:sap://" + resolvedHost + ":" + resolvedPort
          + "/?databaseName=" + database;
      case "informix" -> "jdbc:informix-sqli://" + resolvedHost + ":" + resolvedPort
          + "/" + database;
      default -> throw new IllegalStateException("Unhandled JDBC driver: " + driver);
    };
  }

  private static void reject(boolean condition, String message) {
    if (condition) throw usage(message);
  }

  private static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }

  private static void requireNoOperands(RawArguments raw, String command) {
    if (!raw.positionals.isEmpty()) throw usage(command + " accepts no operands");
  }

  private static boolean hasNonHelpOptions(RawArguments raw) {
    return raw.scriptFile != null || raw.scriptText != null || raw.inputFile != null
        || raw.inputText != null || raw.inputFormat != null || raw.pattern != null
        || raw.output != null || raw.reportFormat != null || raw.cache || raw.name != null
        || raw.cacheDirectory != null || raw.olderThan != null || raw.all
        || raw.config != null || raw.removedProperties != null || raw.paramsFile != null
        || !raw.params.isEmpty() || raw.parquetRoot != null || raw.parquetRecord != null
        || raw.debug || raw.noKeyInference || hasJdbc(raw);
  }

  private static void rejectNonHelpOptions(RawArguments raw, String command) {
    reject(hasNonHelpOptions(raw), command + " accepts no options");
  }

  private static void validateHelpOptions(
      Command command, String subcommand, RawArguments raw) {
    if (command == Command.HELP) {
      rejectNonHelpOptions(raw, "help");
      validateHelpTopic(raw.positionals);
      return;
    }
    if (command == Command.VERSION) {
      rejectNonHelpOptions(raw, "version");
      return;
    }

    Command effective = command == Command.IMPLICIT ? implicitCommand(raw) : command;
    switch (effective) {
      case RUN -> validateRunOptionOwnership(raw);
      case CONVERT -> validateConvertOptionOwnership(raw);
      case CATALOG -> validateCatalogOptionOwnership(raw);
      case CACHE -> validateCacheOptionOwnership(subcommand, raw);
      case HELP, VERSION, IMPLICIT -> throw new IllegalStateException("invalid help route");
    }
    validateKnownOptionValues(raw);
  }

  private static void validateRunOptionOwnership(RawArguments raw) {
    reject(raw.pattern != null, "--pattern is only valid for catalog");
    reject(raw.reportFormat != null, "--report-format is not valid for run");
    reject(raw.all || raw.olderThan != null, "cache clear options are not valid for run");
  }

  private static void validateConvertOptionOwnership(RawArguments raw) {
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for conversion");
    reject(raw.cache || raw.name != null, "cache options are not valid for conversion");
    reject(raw.pattern != null, "--pattern is not valid for conversion");
    reject(raw.reportFormat != null, "--report-format is not valid for conversion");
    reject(raw.cacheDirectoryExplicit, "--cache-dir is not valid for conversion");
    reject(raw.config != null, "--config is not valid for conversion");
    reject(hasJdbc(raw), "database options are not valid for conversion");
    reject(raw.noKeyInference, "--no-key-inference is not valid for conversion");
    reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for conversion");
  }

  private static void validateCatalogOptionOwnership(RawArguments raw) {
    reject(raw.output != null, "--output is not valid for catalog");
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for catalog");
    reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    reject(raw.all || raw.olderThan != null,
        "cache clear options are not valid for catalog");
  }

  private static void validateCacheOptionOwnership(String subcommand, RawArguments raw) {
    reject(raw.cache, "--cache is not valid inside a cache subcommand");
    reject(raw.output != null, "cache commands use --report-format, not --output");
    reject(raw.scriptFile != null || raw.scriptText != null,
        "script options are not valid for cache commands");
    reject(raw.pattern != null, "--pattern is only valid for catalog");
    reject(raw.noKeyInference, "--no-key-inference is only valid for run");
    reject(hasJdbc(raw), "database options are not valid for cache commands");
    if (subcommand != null && !subcommand.equals("load")) {
      reject(raw.inputFile != null || raw.inputText != null || raw.inputFormat != null,
          "input options are not valid for cache " + subcommand);
      reject(raw.paramsFile != null || !raw.params.isEmpty(),
          "parameters are not valid for cache " + subcommand);
      reject(raw.parquetRoot != null || raw.parquetRecord != null,
          "Parquet options are not valid for cache " + subcommand);
    }
  }

  private static void validateKnownOptionValues(RawArguments raw) {
    if (raw.inputFormat != null) inputType(raw.inputFormat);
    if (raw.output != null) outputType(raw.output);
    if (raw.reportFormat != null) reportFormat(raw.reportFormat);
    if (raw.databaseType != null) knownDriver(raw.databaseType);
    if (raw.jdbcDriver != null) knownDriver(raw.jdbcDriver);
    if (raw.olderThan != null) age(raw.olderThan);
    if (raw.name != null) cacheName(raw.name);
  }

  private static void validateHelpTopic(List<String> topic) {
    if (topic.size() > 2) {
      throw usage("help accepts a command and optional subcommand");
    }
    if (topic.isEmpty()) return;
    String first = topic.getFirst().toLowerCase(Locale.ROOT);
    Command command = Command.from(first);
    boolean namedTopic = Set.of(
        "connection", "database", "db", "jdbc",
        "output", "report-format",
        "cache-dir", "cache-directory",
        "parameters", "parameter", "params", "config", "parquet", "query")
        .contains(first);
    if ((command == null || command == Command.IMPLICIT) && !namedTopic) {
      throw usage("Unknown help topic: " + topic.getFirst());
    }
    if (topic.size() == 2) {
      String subcommand = topic.get(1).toLowerCase(Locale.ROOT);
      if (namedTopic || command != Command.CACHE
          || !Set.of("load", "use", "list", "clear").contains(subcommand)) {
        throw usage("Unknown help topic: " + String.join(" ", topic));
      }
    }
  }

  private static void validateLexicalSyntax(String[] args) {
    Set<String> seenBooleans = new HashSet<>();
    boolean options = true;
    for (int index = 0; index < args.length; index++) {
      String token = args[index];
      if (options && token.equals("--")) {
        options = false;
        continue;
      }
      if (!options) continue;
      if (token.startsWith("--") && token.contains("=")) {
        throw usage("Long options do not accept '=' syntax: " + token);
      }
      if (token.startsWith("-") && !token.startsWith("--") && token.length() > 2) {
        throw usage("Short options do not accept attached values or bundles: " + token);
      }
      String booleanName = BOOLEAN_OPTIONS.get(token);
      if (booleanName != null && !seenBooleans.add(booleanName)) {
        throw usage("Duplicate option: " + token);
      }
      if (VALUE_OPTIONS.contains(token) && index + 1 < args.length) index++;
    }
  }

  private static boolean containsHelpFlag(List<String> arguments) {
    return arguments.contains("--help") || arguments.contains("-h");
  }

  private enum Command {
    IMPLICIT, RUN, CONVERT, CATALOG, CACHE, HELP, VERSION;

    static Command from(String value) {
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "run" -> RUN;
        case "convert" -> CONVERT;
        case "catalog" -> CATALOG;
        case "cache" -> CACHE;
        case "help" -> HELP;
        case "version" -> VERSION;
        default -> null;
      };
    }
  }

  private record Route(Command command, String subcommand, List<String> remaining) {
  }

  private record Slots(String script, String data) {
  }

  @CommandLine.Command(name = "nq", mixinStandardHelpOptions = false)
  private static final class RawArguments {
    @Parameters(arity = "0..*")
    private List<String> positionals = new ArrayList<>();

    @Option(names = {"-f", "--script-file"}) private String scriptFile;
    @Option(names = {"-e", "--script-text"}) private String scriptText;
    @Option(names = {"-i", "--input-file"}) private String inputFile;
    @Option(names = "--input-text") private String inputText;
    @Option(names = {"-t", "--input-format"}) private String inputFormat;
    @Option(names = {"-m", "--pattern"}) private String pattern;
    @Option(names = {"-o", "--output"}) private String output;
    @Option(names = {"-r", "--report-format"}) private String reportFormat;
    @Option(names = "--cache") private boolean cache;
    @Option(names = "--name") private String name;
    private String cacheDirectory;
    private boolean cacheDirectoryExplicit;
    @Option(names = "--cache-dir")
    private void cacheDirectory(String value) {
      cacheDirectory = value;
      cacheDirectoryExplicit = true;
    }
    @Option(names = "--older-than") private String olderThan;
    @Option(names = "--all") private boolean all;
    @Option(names = "--config") private String config;
    @Option(names = {"-p", "--properties"}) private String removedProperties;
    @Option(names = "--params-file") private String paramsFile;
    @Option(names = "--param") private List<String> params = new ArrayList<>();
    @Option(names = "--parquet-root") private String parquetRoot;
    @Option(names = "--parquet-record") private String parquetRecord;
    @Option(names = "--debug") private boolean debug;
    @Option(names = "--no-key-inference") private boolean noKeyInference;
    @Option(names = "--db") private String databaseType;
    @Option(names = "--database") private String database;
    @Option(names = "--host") private String host;
    @Option(names = "--port") private String port;
    @Option(names = "--user") private String user;
    @Option(names = "--password") private String password;
    @Option(names = "--jdbc-username") private String jdbcUsername;
    @Option(names = "--jdbc-password") private String jdbcPassword;
    @Option(names = "--jdbc-database") private String jdbcDatabase;
    @Option(names = "--jdbc-driver") private String jdbcDriver;
    @Option(names = "--jdbc-class-name") private String jdbcClassName;
    @Option(names = "-h") private boolean briefHelp;
    @Option(names = "--help") private boolean help;
    @Option(names = "--version") private boolean version;
  }
}
