package blater.nq;

import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import blater.nq.report.ReportFormat;
import blater.nq.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/*
 * Responsibility: Parses CLI arguments and properties files into the
 * runtime parameter map.
 */
public final class ParameterParser {
  public static final String INPUT_FILENAME = "NSQL_INPUTFILE";
  public static final String INPUT_TYPE_PARAM = "NSQL_INPUT_TYPE";
  public static final String STDIN_INPUT = "-";

  public static final String SCRIPT_FILE_PARAM = "NSQL_SCRIPTFILE";
  public static final String SCRIPT_TEXT_PARAM = "NSQL_SCRIPT";
  public static final String HELP_PARAM = "NSQL_HELP";
  public static final String VERSION_PARAM = "NSQL_VERSION";
  public static final String BRIEF_HELP = "-h";
  public static final String COMMAND_PARAM = "NSQL_COMMAND";
  public static final String COMMAND_RUN = "run";
  public static final String COMMAND_CONVERT = "convert";
  public static final String COMMAND_CATALOG = "catalog";
  public static final String COMMAND_CACHE_LOAD = "cache-load";
  public static final String COMMAND_CACHE_USE = "cache-use";
  public static final String COMMAND_CACHE_LIST = "cache-list";
  public static final String COMMAND_CACHE_CLEAR = "cache-clear";
  public static final String CATALOG_PATTERN_PARAM = "NSQL_CATALOG_PATTERN";

  public static final String CACHE_CLEAR_ALL_PARAM = "NSQL_CACHE_CLEAR_ALL";
  public static final String CACHE_CLEAR_TARGET_PARAM = "NSQL_CACHE_CLEAR_TARGET";
  public static final String CACHE_CLEAR_OLDER_THAN_PARAM = "NSQL_CACHE_CLEAR_OLDER_THAN";
  public static final String CACHE_LIST_PARAM = "NSQL_CACHE_LIST";
  public static final String CACHE_USE_PARAM = "NSQL_CACHE_USE";
  public static final String STATE_DIR_PARAM = "NSQL_STATE_DIR";
  /** @deprecated use {@link #STATE_DIR_PARAM}. */
  @Deprecated
  public static final String CACHE_DIR_PARAM = STATE_DIR_PARAM;

  public static final String JDBC_PROPS_FILE_PARAM = "NSQL_JDBC_PROPS_FILE";
  public static final String OUTPUT_TYPE_PARAM = "NSQL_OUTPUT_TYPE";
  public static final String REPORT_FORMAT_PARAM = "NSQL_REPORT_FORMAT";
  public static final String DEBUG_PARAM = "NSQL_DEBUG";
  public static final String NO_KEY_INFERENCE_PARAM = "NSQL_NO_KEY_INFERENCE";
  public static final String CACHE_MODE_PARAM = "NSQL_CACHE";
  public static final String PARQUET_ROOT_PARAM = "NSQL_PARQUET_ROOT";
  public static final String PARQUET_RECORD_PARAM = "NSQL_PARQUET_RECORD";
  public static final String JDBC_DRIVER_PARAM = "jdbc.driver";
  public static final String JDBC_CLASS_NAME_PARAM = "jdbc.class.name";
  public static final String JDBC_DATABASE_PARAM = "jdbc.database";
  public static final String JDBC_USERNAME_PARAM = "jdbc.username";
  public static final String JDBC_PASSWORD_PARAM = "jdbc.password";

  // Returns the runtime parameter map assembled from CLI arguments and property files.
  public static Map<String, String> parse(String... args) {
    if (args.length == 1 && "--version".equals(args[0])) {
      return Map.of(VERSION_PARAM, "true");
    }
    String helpTopic = helpTopic(args);
    if (helpTopic != null) {
      return Map.of(HELP_PARAM, helpTopic);
    }

    Command command = command(args);
    int firstOption = command.firstOption();

    Map<String, String> propertyParameters = new LinkedHashMap<>();
    Map<String, String> commandParameters = new LinkedHashMap<>();
    commandParameters.put(COMMAND_PARAM, command.name);
    applyCommandDefaults(command, commandParameters);
    String databaseType = null;
    String databaseName = null;
    String host = null;
    String port = null;

    for (int i = firstOption; i < args.length; i++) {
      String argument = args[i];
      int equals = argument.startsWith("--") ? argument.indexOf('=') : -1;
      String option = equals < 0 ? argument : argument.substring(0, equals);
      String attachedValue = equals < 0 ? null : argument.substring(equals + 1);

      switch (option) {
        case "-p", "--properties" -> {
          String filename = requiredValue(
              args, i, attachedValue, "no properties filename supplied");
          i = nextIndex(i, attachedValue);
          addParametersFromFile(propertyParameters, filename);
        }

        case "--cache" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(CACHE_MODE_PARAM, "true");
        }
        case "--state-dir" -> i = putValue(
            commandParameters, STATE_DIR_PARAM,
            args, i, attachedValue, "no state directory supplied");
        case "--name" -> i = putValue(
            commandParameters, command == Command.CACHE_USE ? CACHE_USE_PARAM : CACHE_CLEAR_TARGET_PARAM,
            args, i, attachedValue, "no cache name supplied");
        case "--older-than" -> i = putValue(
            commandParameters, CACHE_CLEAR_OLDER_THAN_PARAM,
            args, i, attachedValue, "no cache age supplied");
        case "--all" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(CACHE_CLEAR_ALL_PARAM, "true");
        }

        case "--script-file" -> i = putExclusiveValue(
            commandParameters, SCRIPT_FILE_PARAM, SCRIPT_TEXT_PARAM,
            args, i, attachedValue, "no script filename supplied");
        case "--script-text" -> i = putExclusiveValue(
            commandParameters, SCRIPT_TEXT_PARAM, SCRIPT_FILE_PARAM,
            args, i, attachedValue, "no script text supplied");
        case "--input-file" -> {
          String value = attachedValue;
          if (value == null && i + 1 < args.length && STDIN_INPUT.equals(args[i + 1])) {
            value = args[++i];
          } else if (value == null) {
            value = requiredValue(args, i, null, "no input filename supplied");
            i++;
          }
          commandParameters.put(INPUT_FILENAME, value);
        }
        case "--input-format" -> {
          String value = requiredValue(
              args, i, attachedValue, "no input format supplied");
          i = nextIndex(i, attachedValue);
          commandParameters.put(INPUT_TYPE_PARAM, InputType.fromName(value).name().toLowerCase());
        }
        case "--pattern" -> i = putValue(
            commandParameters, CATALOG_PATTERN_PARAM,
            args, i, attachedValue, "no catalog pattern supplied");
        case "--param" -> {
          String assignment = requiredValue(args, i, attachedValue, "no parameter assignment supplied");
          i = nextIndex(i, attachedValue);
          if (!isParameterAssignment(assignment)) {
            Log.fatal(IllegalArgumentException.class,
                "--param requires a name=value assignment: " + assignment);
          }
          addCommandParameter(commandParameters, assignment);
        }

        case "--parquet-root" -> i = putValue(
            commandParameters, PARQUET_ROOT_PARAM,
            args, i, attachedValue, "no parquet root supplied");
        case "--parquet-record" -> i = putValue(
            commandParameters, PARQUET_RECORD_PARAM,
            args, i, attachedValue, "no parquet record supplied");
        case "--output", "-o" -> {
          String value = requiredValue(args, i, attachedValue, "no output type supplied");
          i = nextIndex(i, attachedValue);
          try {
            commandParameters.put(OUTPUT_TYPE_PARAM, OutputType.fromName(value).name().toLowerCase());
          } catch (IllegalArgumentException ex) {
            Log.fatal(IllegalArgumentException.class, ex.getMessage());
          }
        }

        case "--report-format" -> {
          String value = requiredValue(args, i, attachedValue, "no report format supplied");
          i = nextIndex(i, attachedValue);
          commandParameters.put(REPORT_FORMAT_PARAM, reportFormat(value).name().toLowerCase());
        }

        case "--debug" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(DEBUG_PARAM, "true");
        }
        case "--no-key-inference" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(NO_KEY_INFERENCE_PARAM, "true");
        }
        case "--db" -> {
          databaseType = requiredValue(args, i, attachedValue, "no value supplied for --db");
          i = nextIndex(i, attachedValue);
        }
        case "--database" -> {
          databaseName = requiredValue(args, i, attachedValue, "no value supplied for --database");
          i = nextIndex(i, attachedValue);
        }
        case "--host" -> {
          host = requiredValue(args, i, attachedValue, "no value supplied for --host");
          i = nextIndex(i, attachedValue);
        }
        case "--port" -> {
          port = requiredValue(args, i, attachedValue, "no value supplied for --port");
          i = nextIndex(i, attachedValue);
        }
        case "--user", "--jdbc-username" -> i = putValue(
            commandParameters, JDBC_USERNAME_PARAM,
            args, i, attachedValue, "no value supplied for " + option);
        case "--password", "--jdbc-password" -> i = putValue(
            commandParameters, JDBC_PASSWORD_PARAM,
            args, i, attachedValue, "no value supplied for " + option);
        case "--jdbc-driver" -> i = putValue(
            commandParameters, JDBC_DRIVER_PARAM,
            args, i, attachedValue, "no value supplied for --jdbc-driver");
        case "--jdbc-class-name" -> i = putValue(
            commandParameters, JDBC_CLASS_NAME_PARAM,
            args, i, attachedValue, "no value supplied for --jdbc-class-name");
        case "--jdbc-database" -> i = putValue(
            commandParameters, JDBC_DATABASE_PARAM,
            args, i, attachedValue, "no value supplied for --jdbc-database");

        default -> {
          Log.fatal(IllegalArgumentException.class, "Unexpected argument: " + argument);
        }
      }
    }

    boolean cacheCommand = command.isCacheCommand();
    Map<String, String> parameters = new LinkedHashMap<>(propertyParameters);
    if (!cacheCommand) {
      applySimpleConnection(parameters, databaseType, databaseName, host, port);
    }
    parameters.putAll(commandParameters);
    if (!cacheCommand) {
      applyDriverClassPrecedence(parameters, commandParameters);
      normalizeJdbcDriver(parameters);
    }
    validateCommand(command, parameters);
    if (command == Command.CATALOG) {
      parameters.put(OUTPUT_TYPE_PARAM, reportFormat(parameters).name().toLowerCase());
    }
    return parameters;
  }

  static boolean hasScript(Map<String, String> parameters) {
    return parameters.containsKey(SCRIPT_FILE_PARAM)
        || parameters.containsKey(SCRIPT_TEXT_PARAM);
  }

  public static ReportFormat reportFormat(Map<String, String> parameters) {
    return reportFormat(parameters.get(REPORT_FORMAT_PARAM));
  }

  public static ReportFormat requestedReportFormat(String... args) {
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if (argument.startsWith("--report-format=")) {
        return reportFormat(argument.substring("--report-format=".length()));
      }
      if ("--report-format".equals(argument) && index + 1 < args.length) {
        return reportFormat(args[index + 1]);
      }
    }
    return null;
  }

  private static ReportFormat reportFormat(String value) {
    try {
      return ReportFormat.fromName(value);
    } catch (IllegalArgumentException ex) {
      return Log.fatal(IllegalArgumentException.class, ex.getMessage());
    }
  }

  private static Command command(String[] args) {
    return switch (args[0]) {
      case COMMAND_RUN -> Command.RUN;
      case COMMAND_CONVERT -> Command.CONVERT;
      case COMMAND_CATALOG -> Command.CATALOG;
      case "cache" -> cacheCommand(args);
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unknown command: " + args[0] + ". Expected one of: run, convert, catalog, cache");
    };
  }

  private static Command cacheCommand(String[] args) {
    if (args.length < 2 || args[1].startsWith("-")) {
      return Log.fatal(IllegalArgumentException.class,
          "cache requires a subcommand: load, use, list, or clear");
    }
    return switch (args[1]) {
      case "load" -> Command.CACHE_LOAD;
      case "use" -> Command.CACHE_USE;
      case "list" -> Command.CACHE_LIST;
      case "clear" -> Command.CACHE_CLEAR;
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unknown cache subcommand: " + args[1] + ". Expected one of: load, use, list, clear");
    };
  }

  private static void applyCommandDefaults(Command command, Map<String, String> parameters) {
    switch (command) {
      case CATALOG -> parameters.put(CATALOG_PATTERN_PARAM, "");
      case CACHE_LOAD -> parameters.put(CACHE_MODE_PARAM, "true");
      case CACHE_USE -> parameters.put(CACHE_USE_PARAM, "");
      case CACHE_LIST -> parameters.put(CACHE_LIST_PARAM, "true");
      case CACHE_CLEAR -> { }
      case RUN, CONVERT -> { }
    }
  }

  private static void validateCommand(Command command, Map<String, String> parameters) {
    boolean hasInput = parameters.containsKey(INPUT_FILENAME);
    boolean hasInputFormat = parameters.containsKey(INPUT_TYPE_PARAM);
    boolean hasOutput = parameters.containsKey(OUTPUT_TYPE_PARAM);
    boolean hasConnection = parameters.containsKey(JDBC_DRIVER_PARAM)
        || parameters.containsKey(JDBC_CLASS_NAME_PARAM)
        || parameters.containsKey(JDBC_DATABASE_PARAM)
        || parameters.containsKey(JDBC_USERNAME_PARAM)
        || parameters.containsKey(JDBC_PASSWORD_PARAM);

    if (hasInputFormat && !hasInput) {
      Log.fatal(IllegalArgumentException.class, "--input-format requires --input-file.");
    }
    if (STDIN_INPUT.equals(parameters.get(INPUT_FILENAME)) && !hasInputFormat) {
      Log.fatal(IllegalArgumentException.class,
          "--input-file - requires --input-format because standard input has no filename.");
    }
    if (command != Command.CATALOG && parameters.containsKey(CATALOG_PATTERN_PARAM)) {
      Log.fatal(IllegalArgumentException.class, "--pattern is only valid for catalog.");
    }
    if (command != Command.RUN
        && command != Command.CACHE_LOAD
        && parameters.containsKey(CACHE_MODE_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "--cache is only valid for run; use 'cache load' for standalone loading.");
    }

    switch (command) {
      case RUN -> {
        if (!hasScript(parameters)) {
          Log.fatal(IllegalArgumentException.class,
              "run requires exactly one of --script-file or --script-text.");
        }
        if (Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM)) && !hasInput) {
          Log.fatal(IllegalArgumentException.class, "run --cache requires --input-file.");
        }
        if (Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM)) && hasConnection) {
          Log.fatal(IllegalArgumentException.class,
              "run cannot combine --cache with database connection options.");
        }
        rejectCacheMaintenance(parameters, "run");
      }
      case CONVERT -> {
        requireInput(hasInput, "convert");
        rejectScript(parameters, "convert");
        rejectConnection(hasConnection, "convert");
        reject(parameters, CACHE_MODE_PARAM, "--cache is not valid for convert.");
        reject(parameters, NO_KEY_INFERENCE_PARAM, "--no-key-inference is not valid for convert.");
        rejectCacheMaintenance(parameters, "convert");
        reject(parameters, CATALOG_PATTERN_PARAM, "--pattern is not valid for convert.");
      }
      case CATALOG -> {
        rejectScript(parameters, "catalog");
        if (hasOutput) {
          Log.fatal(IllegalArgumentException.class,
              "catalog uses --report-format; --output is only for run and convert.");
        }
        reject(parameters, CACHE_MODE_PARAM,
            "catalog reads --input-file through an ephemeral cache; --cache is not valid.");
        if (hasInput && hasConnection) {
          Log.fatal(IllegalArgumentException.class,
              "catalog accepts exactly one source: --input-file, a database connection, or the active cache.");
        }
        rejectCacheMaintenance(parameters, "catalog");
      }
      case CACHE_LOAD -> {
        requireInput(hasInput, "cache load");
        rejectScript(parameters, "cache load");
        rejectConnection(hasConnection, "cache load");
        rejectOutput(hasOutput, "cache load");
        rejectCacheMaintenance(parameters, "cache load");
      }
      case CACHE_USE -> {
        rejectInput(hasInput, "cache use");
        rejectScript(parameters, "cache use");
        rejectConnection(hasConnection, "cache use");
        rejectOutput(hasOutput, "cache use");
        String name = parameters.get(CACHE_USE_PARAM);
        if (name == null || name.isBlank()) {
          Log.fatal(IllegalArgumentException.class, "cache use requires --name.");
        }
      }
      case CACHE_LIST -> {
        rejectInput(hasInput, "cache list");
        rejectScript(parameters, "cache list");
        rejectConnection(hasConnection, "cache list");
        rejectOutput(hasOutput, "cache list");
        rejectCacheTargets(parameters, "cache list");
      }
      case CACHE_CLEAR -> {
        rejectInput(hasInput, "cache clear");
        rejectScript(parameters, "cache clear");
        rejectConnection(hasConnection, "cache clear");
        rejectOutput(hasOutput, "cache clear");
        int targets = (parameters.containsKey(CACHE_CLEAR_ALL_PARAM) ? 1 : 0)
            + (parameters.containsKey(CACHE_CLEAR_TARGET_PARAM) ? 1 : 0)
            + (parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM) ? 1 : 0);
        if (targets != 1) {
          Log.fatal(IllegalArgumentException.class,
              "cache clear requires exactly one of --all, --name, or --older-than.");
        }
      }
    }
  }

  private static void requireInput(boolean hasInput, String command) {
    if (!hasInput) {
      Log.fatal(IllegalArgumentException.class, command + " requires --input-file.");
    }
  }

  private static void rejectConnection(boolean hasConnection, String command) {
    if (hasConnection) {
      Log.fatal(IllegalArgumentException.class,
          "Database connection options are not valid for " + command + ".");
    }
  }

  private static void rejectInput(boolean hasInput, String command) {
    if (hasInput) {
      Log.fatal(IllegalArgumentException.class, "--input-file is not valid for " + command + ".");
    }
  }

  private static void rejectScript(Map<String, String> parameters, String command) {
    if (hasScript(parameters)) {
      Log.fatal(IllegalArgumentException.class,
          "Script options are not valid for " + command + ".");
    }
  }

  private static void rejectCacheMaintenance(Map<String, String> parameters, String command) {
    if (parameters.containsKey(CACHE_USE_PARAM)
        || parameters.containsKey(CACHE_LIST_PARAM)
        || parameters.containsKey(CACHE_CLEAR_TARGET_PARAM)
        || parameters.containsKey(CACHE_CLEAR_ALL_PARAM)
        || parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "Cache maintenance options are not valid for " + command + ".");
    }
  }

  private static void rejectCacheTargets(Map<String, String> parameters, String command) {
    if (parameters.containsKey(CACHE_CLEAR_TARGET_PARAM)
        || parameters.containsKey(CACHE_CLEAR_ALL_PARAM)
        || parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "Cache target options are not valid for " + command + ".");
    }
  }

  private static void rejectOutput(boolean hasOutput, String command) {
    if (hasOutput) {
      Log.fatal(IllegalArgumentException.class,
          command + " uses --report-format; --output is only for run and convert.");
    }
  }

  private static void reject(Map<String, String> parameters, String key, String message) {
    if (parameters.containsKey(key)) {
      Log.fatal(IllegalArgumentException.class, message);
    }
  }

  private enum Command {
    RUN(COMMAND_RUN, 1),
    CONVERT(COMMAND_CONVERT, 1),
    CATALOG(COMMAND_CATALOG, 1),
    CACHE_LOAD(COMMAND_CACHE_LOAD, 2),
    CACHE_USE(COMMAND_CACHE_USE, 2),
    CACHE_LIST(COMMAND_CACHE_LIST, 2),
    CACHE_CLEAR(COMMAND_CACHE_CLEAR, 2);

    private final String name;
    private final int firstOption;

    Command(String name, int firstOption) {
      this.name = name;
      this.firstOption = firstOption;
    }

    int firstOption() {
      return firstOption;
    }

    boolean isCacheCommand() {
      return this == CACHE_LOAD || this == CACHE_USE || this == CACHE_LIST || this == CACHE_CLEAR;
    }
  }

  private static String helpTopic(String[] args) {
    if (args.length == 0) {
      return BRIEF_HELP;
    }
    if ("help".equals(args[0])) {
      return args.length == 1 ? "help" : args[1];
    }
    for (int index = 0; index < args.length; index++) {
      String argument = args[index];
      if ("-h".equals(argument)) {
        return BRIEF_HELP;
      }
      if (argument.startsWith("--help=")) {
        return argument.substring("--help=".length());
      }
      if ("--help".equals(argument)) {
        if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
          return args[index + 1];
        }
        return "";
      }
    }
    return null;
  }

  private static String requiredValue(
      String[] args, int index, String attachedValue, String missingMessage) {
    if (attachedValue != null) {
      return attachedValue;
    }
    requireValueAfter(args, index, missingMessage);
    return args[index + 1];
  }

  private static int putValue(
      Map<String, String> parameters,
      String key,
      String[] args,
      int index,
      String attachedValue,
      String missingMessage) {
    parameters.put(key, requiredValue(args, index, attachedValue, missingMessage));
    return nextIndex(index, attachedValue);
  }

  private static int putExclusiveValue(
      Map<String, String> parameters,
      String key,
      String conflictingKey,
      String[] args,
      int index,
      String attachedValue,
      String missingMessage) {
    if (parameters.containsKey(conflictingKey) || parameters.containsKey(key)) {
      Log.fatal(IllegalArgumentException.class,
          "run requires exactly one of --script-file or --script-text.");
    }
    return putValue(parameters, key, args, index, attachedValue, missingMessage);
  }

  private static int nextIndex(int index, String attachedValue) {
    return attachedValue == null ? index + 1 : index;
  }

  private static void requireNoAttachedValue(String argument, String attachedValue) {
    if (attachedValue != null) {
      Log.fatal(IllegalArgumentException.class, "Unknown option: " + argument);
    }
  }

  private static void addParametersFromFile(Map<String, String> parameters, String filename) {
    if (filename.endsWith(".properties")) {
      addParametersFromPropFile(parameters, filename);
    } else if (filename.endsWith(".xml")) {
      addParametersFromXmlFile(parameters, filename);
    } else if (filename.endsWith(".json")) {
      addParametersFromJsonFile(parameters, filename);
    } else if (filename.endsWith(".yaml")) {
      addParametersFromYamlFile(parameters, filename);
    }
  }

  private static void addCommandParameter(Map<String, String> parameters, String assignment) {
    int equals = assignment.indexOf('=');
    String key = assignment.substring(0, equals);
    if (isNotSystemParam(key)) {
      parameters.put(key, assignment.substring(equals + 1).trim());
    }
  }

  private static void applyDriverClassPrecedence(
      Map<String, String> parameters, Map<String, String> commandParameters) {
    if (commandParameters.containsKey(JDBC_CLASS_NAME_PARAM)
        && !commandParameters.containsKey(JDBC_DRIVER_PARAM)) {
      parameters.remove(JDBC_DRIVER_PARAM);
    }
  }

  private static void applySimpleConnection(
      Map<String, String> parameters,
      String databaseType,
      String databaseName,
      String host,
      String port) {
    if ((host != null || port != null) && databaseType == null) {
      Log.fatal(IllegalArgumentException.class, "--host and --port require --db.");
    }
    if ((databaseType == null) != (databaseName == null)) {
      Log.fatal(IllegalArgumentException.class, "--db and --database must be supplied together.");
    }
    if (databaseType == null) {
      return;
    }

    String driver = normalizeDatabaseType(databaseType);
    if (driver.equals("h2") && (host != null || port != null)) {
      Log.fatal(IllegalArgumentException.class, "--host and --port are not valid for H2.");
    }
    if (requiresExplicitPort(driver) && port == null) {
      Log.fatal(IllegalArgumentException.class, "--port is required for " + driver + ".");
    }

    String username = defaultUsername(driver);
    if (username != null) {
      parameters.putIfAbsent(JDBC_USERNAME_PARAM, username);
    }
    parameters.put(JDBC_DRIVER_PARAM, driver);
    parameters.put(JDBC_DATABASE_PARAM, jdbcUrl(driver, databaseName, host, port));
  }

  private static boolean requiresExplicitPort(String driver) {
    return driver.equals("hana") || driver.equals("informix");
  }

  private static void normalizeJdbcDriver(Map<String, String> parameters) {
    String driver = parameters.get(JDBC_DRIVER_PARAM);
    if (driver != null && !driver.isBlank()) {
      parameters.put(JDBC_DRIVER_PARAM, normalizeDatabaseType(driver));
    }
  }

  private static String normalizeDatabaseType(String value) {
    String normalized = value.trim().toLowerCase().replace('_', '-');
    return switch (normalized) {
      case "h2", "h2db" -> "h2";
      case "oracle", "ojdbc", "ojdbc11" -> "oracle";
      case "sqlserver", "mssql", "mssql-jdbc", "sql-server" -> "sqlserver";
      case "db2", "jcc" -> "db2";
      case "hana", "sap", "sap-hana", "ngdbc" -> "hana";
      case "informix" -> "informix";
      case "mysql", "mysql-connector-j" -> "mysql";
      case "mariadb" -> "mariadb";
      case "postgresql", "postgres", "postgres-jdbc" -> "postgresql";
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported JDBC driver [" + value + "]. Expected one of: "
              + "h2, oracle, sqlserver, db2, hana, informix, mysql, mariadb, postgresql");
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
      case "sqlserver" -> "jdbc:sqlserver://" + resolvedHost + ":" + resolvedPort + ";databaseName=" + database;
      case "db2" -> "jdbc:db2://" + resolvedHost + ":" + resolvedPort + "/" + database;
      case "hana" -> "jdbc:sap://" + resolvedHost + ":" + resolvedPort + "/?databaseName=" + database;
      case "informix" -> "jdbc:informix-sqli://" + resolvedHost + ":" + resolvedPort + "/" + database;
      default -> throw new IllegalArgumentException("Unsupported JDBC driver [" + driver + "].");
    };
  }


  static void addParameterFromMainPropsFile(Map<String, String> parameters, String propertiesString) {
    if (propertiesString == null) return;
    String s = propertiesString.trim();
    if (s.isEmpty()) return;                      // skip blank
    char first = s.charAt(0);
    if (first == '#' || first == '!') return;     // skip comment
    int eq = s.indexOf('=');
    int col = s.indexOf(':');
    int sep;
    if (eq >= 0 && col >= 0) sep = Math.min(eq, col);
    else if (eq >= 0) sep = eq;
    else if (col >= 0) sep = col;
    else sep = -1;
    if (sep < 0) {                                 // no separator -> empty value
      parameters.put(s, "");
      return;
    }
    String key = s.substring(0, sep).trim();
    if (isNotSystemParam(key)) {
      String val = s.substring(sep + 1).trim();
      parameters.put(key, val);
    }
  }

  static boolean isNotSystemParam(String key) {
    return !(key.equals(SCRIPT_FILE_PARAM)
        || key.equals(SCRIPT_TEXT_PARAM)
        || key.equals(COMMAND_PARAM)
        || key.equals(HELP_PARAM)
        || key.equals(VERSION_PARAM)
        || key.equals(CATALOG_PATTERN_PARAM)
        || key.equals(INPUT_FILENAME)
        || key.equals(INPUT_TYPE_PARAM)
        || key.equals(JDBC_PROPS_FILE_PARAM)
        || key.equals(OUTPUT_TYPE_PARAM)
        || key.equals(REPORT_FORMAT_PARAM)
        || key.equals(DEBUG_PARAM)
        || key.equals(NO_KEY_INFERENCE_PARAM)
        || key.equals(CACHE_MODE_PARAM)
        || key.equals(CACHE_DIR_PARAM)
        || key.equals(CACHE_CLEAR_ALL_PARAM)
        || key.equals(CACHE_CLEAR_TARGET_PARAM)
        || key.equals(CACHE_CLEAR_OLDER_THAN_PARAM)
        || key.equals(CACHE_LIST_PARAM)
        || key.equals(CACHE_USE_PARAM)
        || key.equals(PARQUET_ROOT_PARAM)
        || key.equals(PARQUET_RECORD_PARAM));
  }

  static void addParametersFromPropFile(Map<String, String> parameters, String filename) {
    Path propFile = Path.of(filename);
    try (Stream<String> lines = Files.lines(propFile)) {
      lines.forEach(line -> addParameterFromMainPropsFile(parameters, line));
    } catch (IOException e) {
      Log.fatal(IllegalStateException.class, "Could not read properties file: " + filename, e);
    }
  }

  static void addParametersFromXmlFile(Map<String, String> parameters, String filename) {
    // todo - adds the distinct unique set of params from an xml file into parameters array.
    // if an element of the same name appears more than once, the first one wins - its value is stored, others ignored
    // this will call new method addParameterCannotOverride(parameters, key, value));  << note must never be able to overwrite existing parameter entries or system key entry.
  }

  static void addParametersFromJsonFile(Map<String, String> parameters, String filename) {
    // todo - adds the distinct unique set of params from an json file into parameters array.
    // if an element of the same name appears more than once, the first one wins - its value is stored, others ignored
    // this will call new method addParameterCannotOverride(parameters, key, value));  << note must never be able to overwrite existing parameter entries or system key entry.
  }

  static void addParametersFromYamlFile(Map<String, String> parameters, String filename) {
    // todo - adds the distinct unique set of params from an yaml file into parameters array.
    // if an element of the same name appears more than once, the first one wins - its value is stored, others ignored
    // this will call new method addParameterCannotOverride(parameters, key, value));  << note must never be able to overwrite existing parameter entries or system key entry.
  }

  private static void requireValueAfter(String[] args, int index, String missingMessage) {
    if (index + 1 >= args.length || args[index + 1].startsWith("-")) {
      Log.fatal(IllegalArgumentException.class, missingMessage);
    }
  }

  private static boolean isParameterAssignment(String value) {
    if (value == null) {
      return false;
    }
    int eq = value.indexOf('=');
    if (eq <= 0) {
      return false;
    }
    String key = value.substring(0, eq);
    for (int index = 0; index < key.length(); index++) {
      char ch = key.charAt(index);
      if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != '-') {
        return false;
      }
    }
    return true;
  }

}
