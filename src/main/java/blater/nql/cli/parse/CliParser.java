package blater.nql.cli.parse;

import blater.nql.cli.HelpInvocation;
import blater.nql.cli.NqlInvocation;
import blater.nql.inputreader.InputType;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** Parses the agent-friendly CLI into an immutable typed invocation. */
public final class CliParser {
  private final CliConfigurationLoader configurationLoader;
  private final CliInvocationDispatcher dispatcher;

  public CliParser() {
    this(System::getenv, defaultUserHome());
  }

  /** Injectable process settings for hermetic parser tests and embedding. */
  public CliParser(Map<String, String> environment, Path userHome) {
    Map<String, String> copiedEnvironment = Map.copyOf(environment);
    Path normalizedUserHome = userHome.toAbsolutePath().normalize();
    configurationLoader = new CliConfigurationLoader(() -> copiedEnvironment);
    dispatcher = new CliInvocationDispatcher(
        new CliBindingSupport(() -> normalizedUserHome));
  }

  private CliParser(Supplier<Map<String, String>> environment, Path userHome) {
    Path normalizedUserHome = userHome.toAbsolutePath().normalize();
    configurationLoader = new CliConfigurationLoader(environment);
    dispatcher = new CliInvocationDispatcher(
        new CliBindingSupport(() -> normalizedUserHome));
  }

  public NqlInvocation parse(String... arguments) {
    String[] safeArguments = arguments == null ? new String[0] : arguments.clone();
    if (safeArguments.length == 0) {
      return new HelpInvocation(List.of(), true);
    }
    CliArgumentRouter.Route route = CliArgumentRouter.route(safeArguments);
    RawArguments raw = parseRaw(route.remaining());
    rejectRemovedProperties(raw);
    if (usesOperationalConfiguration(route.command(), raw)) {
      configurationLoader.apply(route.command(), raw);
    }
    return dispatcher.bind(route.command(), route.subcommand(), raw);
  }

  private static RawArguments parseRaw(List<String> arguments) {
    RawArguments raw = new RawArguments();
    try {
      configured(raw).parseArgs(arguments.toArray(String[]::new));
      return raw;
    } catch (CommandLine.ParameterException exception) {
      throw new CliUsageException(exception.getMessage(), exception);
    }
  }

  private static CommandLine configured(RawArguments raw) {
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

  private static void rejectRemovedProperties(RawArguments raw) {
    if (raw.removedProperties != null) {
      throw usage("-p/--properties was removed; use --config for NQL settings "
          + "or --params-file for task parameters");
    }
  }

  private static boolean usesOperationalConfiguration(Command command, RawArguments raw) {
    return !raw.help && !raw.briefHelp && !raw.version && !raw.capabilities
        && command != Command.CAPABILITIES;
  }

  private static Path defaultUserHome() {
    return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
  }

  static boolean isScriptFilename(String value) {
    return value.toLowerCase(Locale.ROOT).endsWith(".nql");
  }

  static boolean isDataFilename(String value) {
    return InputType.supportsFilename(value);
  }

  static boolean equalsWord(String left, String right) {
    return left.equalsIgnoreCase(right);
  }

  static void reject(boolean condition, String message) {
    if (condition) {
      throw usage(message);
    }
  }

  static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }

  enum Command {
    IMPLICIT, RUN, CONVERT, CATALOG, CACHE, CAPABILITIES, HELP, VERSION;

    static Command from(String value) {
      return switch (value.toLowerCase(Locale.ROOT)) {
        case "run" -> RUN;
        case "convert" -> CONVERT;
        case "catalog" -> CATALOG;
        case "cache" -> CACHE;
        case "capabilities" -> CAPABILITIES;
        case "help" -> HELP;
        case "version" -> VERSION;
        default -> null;
      };
    }
  }

  @CommandLine.Command(name = "nql", mixinStandardHelpOptions = false)
  static final class RawArguments {
    @Parameters(arity = "0..*")
    List<String> positionals = new ArrayList<>();

    @Option(names = {"-f", "--script-file"}) String scriptFile;
    @Option(names = {"-e", "--script-text"}) String scriptText;
    @Option(names = {"-i", "--input-file"}) String inputFile;
    @Option(names = "--input-text") String inputText;
    @Option(names = {"-t", "--input-format"}) String inputFormat;
    @Option(names = {"-m", "--pattern"}) String pattern;
    @Option(names = {"-o", "--output"}) String output;
    @Option(names = {"-r", "--report-format"}) String reportFormat;
    @Option(names = "--cache") boolean cache;
    @Option(names = "--name") String name;
    String cacheDirectory;
    boolean cacheDirectoryExplicit;

    @Option(names = "--cache-dir")
    void cacheDirectory(String value) {
      cacheDirectory = value;
      cacheDirectoryExplicit = true;
    }

    @Option(names = "--older-than") String olderThan;
    @Option(names = "--all") boolean all;
    @Option(names = "--config") String config;
    @Option(names = {"-p", "--properties"}) String removedProperties;
    @Option(names = "--params-file") String paramsFile;
    @Option(names = "--param") List<String> params = new ArrayList<>();
    @Option(names = "--parquet-root") String parquetRoot;
    @Option(names = "--parquet-record") String parquetRecord;
    @Option(names = "--debug") boolean debug;
    @Option(names = "--no-key-inference") boolean noKeyInference;
    @Option(names = "--db") String databaseType;
    @Option(names = "--database") String database;
    @Option(names = "--host") String host;
    @Option(names = "--port") String port;
    @Option(names = "--user") String user;
    @Option(names = "--password") String password;
    @Option(names = "--jdbc-username") String jdbcUsername;
    @Option(names = "--jdbc-password") String jdbcPassword;
    @Option(names = "--jdbc-database") String jdbcDatabase;
    @Option(names = "--jdbc-driver") String jdbcDriver;
    @Option(names = "--jdbc-class-name") String jdbcClassName;
    @Option(names = "-h") boolean briefHelp;
    @Option(names = "--help") boolean help;
    @Option(names = "--version") boolean version;
    @Option(names = "--capabilities") boolean capabilities;
  }
}
