package blater.nq;

import blater.nq.inputreader.InputType;
import blater.nq.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/*
 * Responsibility: Parses CLI arguments and properties files into the
 * runtime parameter map.
 */
public final class ParameterParser {
  public static final String INPUT_FILENAME = "NSQL_INPUTFILE";
  public static final String INPUT_TYPE_PARAM = "NSQL_INPUT_TYPE";
  public static final String STDIN_SOURCE_PARAM = "NSQL_STDIN_SOURCE";
  public static final String STDIN_INPUT = "-";

  public static final String SCRIPT_FILE_PARAM = "NSQL_SCRIPTFILE";
  public static final String SCRIPT_TEXT_PARAM = "NSQL_SCRIPT";
  public static final String HELP_PARAM = "NSQL_HELP";
  public static final String VERSION_PARAM = "NSQL_VERSION";
  public static final String BRIEF_HELP = "-h";
  public static final String CATALOG_PATTERN_PARAM = "NSQL_CATALOG_PATTERN";

  public static final String CACHE_CLEAR_ALL_PARAM = "NSQL_CACHE_CLEAR_ALL";
  public static final String CACHE_CLEAR_TARGET_PARAM = "NSQL_CACHE_CLEAR_TARGET";
  public static final String CACHE_CLEAR_OLDER_THAN_PARAM = "NSQL_CACHE_CLEAR_OLDER_THAN";
  public static final String CACHE_LIST_PARAM = "NSQL_CACHE_LIST";
  public static final String CACHE_USE_PARAM = "NSQL_CACHE_USE";
  public static final String CACHE_DIR_PARAM = "NSQL_CACHE_DIR";

  public static final String JDBC_PROPS_FILE_PARAM = "NSQL_JDBC_PROPS_FILE";
  public static final String OUTPUT_TYPE_PARAM = "NSQL_OUTPUT_TYPE";
  public static final String DEBUG_PARAM = "NSQL_DEBUG";
  public static final String NO_KEY_INFERENCE_PARAM = "NSQL_NO_KEY_INFERENCE";
  public static final String METADATA_REFRESH_PARAM = "NSQL_METADATA_REFRESH";
  public static final String METADATA_EXPIRY_HOURS_PARAM = "NSQL_METADATA_EXPIRY_HOURS";
  public static final String CACHE_MODE_PARAM = "NSQL_CACHE";
  public static final String PARQUET_ROOT_PARAM = "NSQL_PARQUET_ROOT";
  public static final String PARQUET_RECORD_PARAM = "NSQL_PARQUET_RECORD";
  public static final String ANONYMOUS_COLLECTIONS_PARAM = "NSQL_ANONYMOUS_COLLECTIONS";
  public static final String RELATION_ALIAS_PREFIX = "NSQL_RELATION_ALIAS.";
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

    List<String> positionals = new ArrayList<>();
    Map<String, String> propertyParameters = new LinkedHashMap<>();
    Map<String, String> commandParameters = new LinkedHashMap<>();
    String databaseType = null;
    String databaseName = null;
    String host = null;
    String port = null;

    for (int i = 0; i < args.length; i++) {
      String argument = args[i];
      int equals = argument.startsWith("--") ? argument.indexOf('=') : -1;
      String option = equals < 0 ? argument : argument.substring(0, equals);
      String attachedValue = equals < 0 ? null : argument.substring(equals + 1);

      switch (option) {
        case "-p" -> {
          String filename = requiredValue(
              args, i, attachedValue, "no properties filename supplied");
          i = nextIndex(i, attachedValue);
          addParametersFromFile(propertyParameters, filename);
        }

        case "--cache", "-c" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(CACHE_MODE_PARAM, "true");
        }
        case "--cache-dir" -> i = putValue(
            commandParameters, CACHE_DIR_PARAM,
            args, i, attachedValue, "no cache directory supplied");
        case "--list-caches" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(CACHE_LIST_PARAM, "true");
        }
        case "--use-cache" -> i = putValue(
            commandParameters, CACHE_USE_PARAM,
            args, i, attachedValue, "no cache source supplied");
        case "--clear-cache" -> {
          if (attachedValue != null) {
            commandParameters.put(CACHE_CLEAR_TARGET_PARAM, attachedValue);
          } else if (hasOptionalValue(args, i)) {
            commandParameters.put(CACHE_CLEAR_TARGET_PARAM, args[++i]);
          } else {
            commandParameters.put(CACHE_CLEAR_ALL_PARAM, "true");
          }
        }
        case "--clear-cache-older-than" -> i = putValue(
            commandParameters, CACHE_CLEAR_OLDER_THAN_PARAM,
            args, i, attachedValue, "no cache age supplied");

        case "--parquet-root" -> i = putValue(
            commandParameters, PARQUET_ROOT_PARAM,
            args, i, attachedValue, "no parquet root supplied");
        case "--parquet-record" -> i = putValue(
            commandParameters, PARQUET_RECORD_PARAM,
            args, i, attachedValue, "no parquet record supplied");
        case "--anonymous-collections" -> i = putValue(
            commandParameters, ANONYMOUS_COLLECTIONS_PARAM,
            args, i, attachedValue, "no anonymous collection mode supplied");
        case "--relation-alias" -> i = putRepeatedValue(
            commandParameters, RELATION_ALIAS_PREFIX,
            args, i, attachedValue, "no relation alias supplied");

        case "--output", "-o" -> i = putValue(
            commandParameters, OUTPUT_TYPE_PARAM,
            args, i, attachedValue, "no output type supplied");

        case "--input", "-i" -> {
          String value = requiredValue(
              args, i, attachedValue, "no standard input type supplied");
          i = nextIndex(i, attachedValue);
          commandParameters.put(INPUT_TYPE_PARAM, InputType.fromName(value).name().toLowerCase());
        }

        case "--debug" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(DEBUG_PARAM, "true");
        }
        case "--no-key-inference" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(NO_KEY_INFERENCE_PARAM, "true");
        }
        case "--metadata-refresh" -> {
          requireNoAttachedValue(argument, attachedValue);
          commandParameters.put(METADATA_REFRESH_PARAM, "true");
        }
        case "--metadata-expiry-hours" -> {
          String value = requiredValue(
              args, i, attachedValue, "no metadata expiry supplied");
          i = nextIndex(i, attachedValue);
          try {
            if (Long.parseLong(value) < 0) throw new NumberFormatException();
          } catch (NumberFormatException ex) {
            Log.fatal(IllegalArgumentException.class,
                "--metadata-expiry-hours requires a non-negative whole number.");
          }
          commandParameters.put(METADATA_EXPIRY_HOURS_PARAM, value);
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
          if (argument.startsWith("-")) {
            Log.fatal(IllegalArgumentException.class, "Unknown option: " + argument);
          } else if (isParameterAssignment(argument) && !fileExists(argument)) {
            addCommandParameter(commandParameters, argument);
          } else {
            positionals.add(argument);
          }
        }
      }
    }

    boolean cacheCommand = isCacheCommand(commandParameters);
    Map<String, String> parameters = new LinkedHashMap<>(propertyParameters);
    if (!cacheCommand) {
      applySimpleConnection(parameters, databaseType, databaseName, host, port);
    }
    parameters.putAll(commandParameters);
    if (!cacheCommand) {
      applyDriverClassPrecedence(parameters, commandParameters);
      normalizeJdbcDriver(parameters);
    }
    resolvePositionals(parameters, positionals);
    validateStandardInputOptions(parameters);
    validateStandaloneInputOptions(parameters);
    validateMaterializationOptions(parameters);
    return parameters;
  }

  private static void validateStandardInputOptions(Map<String, String> parameters) {
    if (!parameters.containsKey(INPUT_TYPE_PARAM)) return;

    if (parameters.containsKey(CACHE_LIST_PARAM)
        || parameters.containsKey(CACHE_USE_PARAM)
        || parameters.containsKey(CACHE_CLEAR_TARGET_PARAM)
        || parameters.containsKey(CACHE_CLEAR_ALL_PARAM)
        || parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)
        || parameters.containsKey(METADATA_REFRESH_PARAM)
        || parameters.containsKey(METADATA_EXPIRY_HOURS_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "--input is not valid for cache or metadata commands.");
    }
  }

  private static void validateStandaloneInputOptions(Map<String, String> parameters) {
    if (!isStandaloneInputCommand(parameters)) return;

    boolean cacheMode = Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM));
    if (cacheMode && parameters.containsKey(OUTPUT_TYPE_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "--output is not valid when loading a cache without a script.");
    }
    if (!cacheMode && parameters.containsKey(CACHE_DIR_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "--cache-dir requires --cache when no script is supplied.");
    }
  }

  private static boolean isStandaloneInputCommand(Map<String, String> parameters) {
    return parameters.containsKey(INPUT_FILENAME)
        && !hasScript(parameters)
        && !parameters.containsKey(CATALOG_PATTERN_PARAM)
        && !parameters.containsKey(METADATA_REFRESH_PARAM)
        && !parameters.containsKey(METADATA_EXPIRY_HOURS_PARAM);
  }

  static boolean hasScript(Map<String, String> parameters) {
    return parameters.containsKey(SCRIPT_FILE_PARAM)
        || parameters.containsKey(SCRIPT_TEXT_PARAM);
  }

  private static void validateMaterializationOptions(Map<String, String> parameters) {
    boolean configured = parameters.containsKey(ANONYMOUS_COLLECTIONS_PARAM)
        || parameters.keySet().stream().anyMatch(key -> key.startsWith(RELATION_ALIAS_PREFIX));
    if (!configured) return;

    if (parameters.containsKey(INPUT_FILENAME)
        && !hasScript(parameters)
        && !Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM))) {
      Log.fatal(IllegalArgumentException.class,
          "Materialization options require a script or --cache.");
    }

    String anonymousMode = parameters.get(ANONYMOUS_COLLECTIONS_PARAM);
    if (anonymousMode != null
        && !anonymousMode.equalsIgnoreCase("merge")
        && !anonymousMode.equalsIgnoreCase("error")) {
      Log.fatal(IllegalArgumentException.class,
          "--anonymous-collections must be one of: merge, error");
    }

    if (parameters.containsKey(CACHE_LIST_PARAM)
        || parameters.containsKey(CACHE_CLEAR_ALL_PARAM)
        || parameters.containsKey(CACHE_CLEAR_TARGET_PARAM)
        || parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)
        || parameters.containsKey(METADATA_REFRESH_PARAM)
        || parameters.containsKey(METADATA_EXPIRY_HOURS_PARAM)) {
      Log.fatal(IllegalArgumentException.class,
          "Materialization options are not valid for this cache or metadata command.");
    }
    if (parameters.containsKey(CACHE_USE_PARAM)) return;
    if (!parameters.containsKey(INPUT_FILENAME)) {
      Log.fatal(IllegalArgumentException.class,
          "Materialization options require an input file load or --use-cache source selection.");
    }
    if (!Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM))
        && (parameters.containsKey(JDBC_DRIVER_PARAM)
        || parameters.containsKey(JDBC_CLASS_NAME_PARAM)
        || parameters.containsKey(JDBC_DATABASE_PARAM))) {
      Log.fatal(IllegalArgumentException.class,
          "Materialization options do not apply to JDBC-backed mapped DML input.");
    }
    InputType inputType = parameters.containsKey(INPUT_TYPE_PARAM)
        ? InputType.fromName(parameters.get(INPUT_TYPE_PARAM))
        : InputType.fromFilename(parameters.get(INPUT_FILENAME));
    if (inputType == InputType.XML || inputType == InputType.PARQUET) {
      Log.fatal(IllegalArgumentException.class,
          "Materialization options are supported for JSON, JSON Lines, YAML, and CSV input.");
    }
  }

  private static String helpTopic(String[] args) {
    if (args.length == 0) {
      return BRIEF_HELP;
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

  private static int putRepeatedValue(
      Map<String, String> parameters,
      String keyPrefix,
      String[] args,
      int index,
      String attachedValue,
      String missingMessage) {
    long count = parameters.keySet().stream().filter(key -> key.startsWith(keyPrefix)).count();
    parameters.put(keyPrefix + String.format("%06d", count),
        requiredValue(args, index, attachedValue, missingMessage));
    return nextIndex(index, attachedValue);
  }

  private static int nextIndex(int index, String attachedValue) {
    return attachedValue == null ? index + 1 : index;
  }

  private static void requireNoAttachedValue(String argument, String attachedValue) {
    if (attachedValue != null) {
      Log.fatal(IllegalArgumentException.class, "Unknown option: " + argument);
    }
  }

  private static boolean hasOptionalValue(String[] args, int index) {
    return index + 1 < args.length && !args[index + 1].startsWith("-");
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

  private static boolean isCacheCommand(Map<String, String> parameters) {
    return Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM))
        || parameters.containsKey(CACHE_CLEAR_ALL_PARAM)
        || parameters.containsKey(CACHE_CLEAR_TARGET_PARAM)
        || parameters.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)
        || parameters.containsKey(CACHE_LIST_PARAM)
        || parameters.containsKey(CACHE_USE_PARAM);
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
        || key.equals(HELP_PARAM)
        || key.equals(VERSION_PARAM)
        || key.equals(CATALOG_PATTERN_PARAM)
        || key.equals(INPUT_FILENAME)
        || key.equals(INPUT_TYPE_PARAM)
        || key.equals(STDIN_SOURCE_PARAM)
        || key.equals(JDBC_PROPS_FILE_PARAM)
        || key.equals(OUTPUT_TYPE_PARAM)
        || key.equals(DEBUG_PARAM)
        || key.equals(NO_KEY_INFERENCE_PARAM)
        || key.equals(METADATA_REFRESH_PARAM)
        || key.equals(METADATA_EXPIRY_HOURS_PARAM)
        || key.equals(CACHE_MODE_PARAM)
        || key.equals(CACHE_DIR_PARAM)
        || key.equals(CACHE_CLEAR_ALL_PARAM)
        || key.equals(CACHE_CLEAR_TARGET_PARAM)
        || key.equals(CACHE_CLEAR_OLDER_THAN_PARAM)
        || key.equals(CACHE_LIST_PARAM)
        || key.equals(CACHE_USE_PARAM)
        || key.equals(PARQUET_ROOT_PARAM)
        || key.equals(PARQUET_RECORD_PARAM)
        || key.equals(ANONYMOUS_COLLECTIONS_PARAM)
        || key.startsWith(RELATION_ALIAS_PREFIX));
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

  private static void resolvePositionals(Map<String, String> params, List<String> positionArguments) {
    if (params.containsKey(INPUT_TYPE_PARAM)) {
      resolveStandardInputPositionals(params, positionArguments);
      return;
    }

    if (params.containsKey(METADATA_REFRESH_PARAM)
        || params.containsKey(METADATA_EXPIRY_HOURS_PARAM)) {
      if (positionArguments.size() == 1
          && Boolean.parseBoolean(params.get(CACHE_MODE_PARAM))
          && isInputFile(positionArguments.getFirst())) {
        params.put(INPUT_FILENAME, positionArguments.getFirst());
      } else if (!positionArguments.isEmpty()) {
        Log.fatal(IllegalArgumentException.class, "Unexpected argument: " + positionArguments.getFirst());
      }
      return;
    }
    if ( params.containsKey(CACHE_LIST_PARAM)
      || params.containsKey(CACHE_USE_PARAM)
      || params.containsKey(CACHE_CLEAR_TARGET_PARAM)
      || params.containsKey(CACHE_CLEAR_ALL_PARAM)
      || params.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM))
    {
      if (!positionArguments.isEmpty()) {
        Log.fatal(IllegalArgumentException.class, "Unexpected argument: " + positionArguments.getFirst());
      }
      return;
    }

    if (!positionArguments.isEmpty() && "catalog".equalsIgnoreCase(positionArguments.getFirst())) {
      resolveCatalog(params, positionArguments.subList(1, positionArguments.size()));
      return;
    }

    switch (positionArguments.size()) {
      case 0 -> {
        if (Boolean.parseBoolean(params.get(CACHE_MODE_PARAM))) {
          Log.fatal(IllegalArgumentException.class, "--cache requires an input file or script.");
        }
        Log.fatal(IllegalArgumentException.class, "No script filename supplied.");
      }
      case 1 -> {
        String argument = positionArguments.getFirst();
        if (isInputFile(argument)) {
          params.put(INPUT_FILENAME, argument);
        } else if (!fileExists(argument)) {
          params.put(SCRIPT_TEXT_PARAM, argument);
        } else {
          params.put(SCRIPT_FILE_PARAM, argument);
        }
      }
      case 2 -> {
        String first = positionArguments.get(0);
        String second = positionArguments.get(1);
        boolean firstExists = fileExists(first);
        boolean secondExists = fileExists(second);

        if (!firstExists && !secondExists) {
          Log.fatal(
              IllegalArgumentException.class,
              "Neither script nor input file exists: " + first + ", " + second);
        } else if (firstExists && secondExists) {
          boolean firstInput = isInputFile(first);
          boolean secondInput = isInputFile(second);
          if (firstInput == secondInput) {
            Log.fatal(
                IllegalArgumentException.class,
                "Could not identify script and input file from: " + first + ", " + second);
          }
          params.put(SCRIPT_FILE_PARAM, firstInput ? second : first);
          params.put(INPUT_FILENAME, firstInput ? first : second);
        } else {
          String existingFile = firstExists ? first : second;
          String missingArgument = firstExists ? second : first;
          if (isInputFile(existingFile)) {
            params.put(INPUT_FILENAME, existingFile);
            params.put(SCRIPT_TEXT_PARAM, missingArgument);
          } else if (isInputFile(missingArgument)) {
            params.put(SCRIPT_FILE_PARAM, existingFile);
            params.put(INPUT_FILENAME, missingArgument);
          } else {
            Log.fatal(
                IllegalArgumentException.class,
                "Input file does not exist: " + missingArgument);
          }
        }
      }
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unexpected argument: " + positionArguments.get(2));
    }
  }

  private static void resolveStandardInputPositionals(
      Map<String, String> params, List<String> positionArguments) {
    if (!positionArguments.isEmpty() && "catalog".equalsIgnoreCase(positionArguments.getFirst())) {
      resolveCatalog(params, positionArguments.subList(1, positionArguments.size()));
      params.put(INPUT_FILENAME, STDIN_INPUT);
      return;
    }

    switch (positionArguments.size()) {
      case 0 -> params.put(INPUT_FILENAME, STDIN_INPUT);
      case 1 -> {
        String script = positionArguments.getFirst();
        if (isInputFile(script)) {
          Log.fatal(IllegalArgumentException.class,
              "--input reads standard input and cannot be combined with an input file: " + script);
        }
        if (fileExists(script)) {
          params.put(SCRIPT_FILE_PARAM, script);
        } else {
          params.put(SCRIPT_TEXT_PARAM, script);
        }
        params.put(INPUT_FILENAME, STDIN_INPUT);
      }
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unexpected argument: " + positionArguments.get(1));
    }
  }

  private static void resolveCatalog(Map<String, String> params, List<String> arguments) {
    boolean selectedCache = Boolean.parseBoolean(params.get(CACHE_MODE_PARAM));
    params.put(CATALOG_PATTERN_PARAM, "");

    switch (arguments.size()) {
      case 0 -> { }
      case 1 -> {
        String argument = arguments.getFirst();
        if (selectedCache && isInputFile(argument)) {
          params.put(INPUT_FILENAME, argument);
        } else {
          params.put(CATALOG_PATTERN_PARAM, argument);
        }
      }
      case 2 -> {
        if (!selectedCache) {
          Log.fatal(IllegalArgumentException.class, "Unexpected argument: " + arguments.get(1));
        }
        String first = arguments.get(0);
        String second = arguments.get(1);
        boolean firstInput = isInputFile(first);
        boolean secondInput = isInputFile(second);
        if (firstInput == secondInput) {
          Log.fatal(
              IllegalArgumentException.class,
              "Could not identify catalog pattern and input file from: " + first + ", " + second);
        }
        params.put(INPUT_FILENAME, firstInput ? first : second);
        params.put(CATALOG_PATTERN_PARAM, firstInput ? second : first);
      }
      default -> Log.fatal(IllegalArgumentException.class, "Unexpected argument: " + arguments.get(2));
    }
  }

  private static boolean fileExists(String value) {
    return value != null && Files.exists(Path.of(value));
  }

  private static boolean isInputFile(String value) {
    return InputType.supportsFilename(value);
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
