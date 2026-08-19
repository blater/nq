package blater.nql.cli.parse;

import blater.nql.cli.CacheName;
import blater.nql.cli.Credentials;
import blater.nql.cli.DriverSelection;
import blater.nql.cli.JdbcConnectionSpec;
import blater.nql.inputreader.InputType;
import blater.nql.outputwriter.OutputType;
import blater.nql.report.ReportFormat;

import java.time.Duration;
import java.util.Locale;

/** Converts and validates typed CLI values, including JDBC connection options. */
final class CliValueParser {
  private CliValueParser() {
  }

  static InputType inputType(String value) {
    if (value == null || value.isBlank()) {
      throw usage("No input format supplied");
    }
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

  static OutputType outputType(String value) {
    if (value.equalsIgnoreCase("md")) {
      return OutputType.MARKDOWN;
    }
    try {
      return OutputType.fromName(value);
    } catch (RuntimeException exception) {
      throw new CliUsageException(exception.getMessage(), exception);
    }
  }

  static ReportFormat reportFormat(String value) {
    try {
      return ReportFormat.fromName(value);
    } catch (RuntimeException exception) {
      throw new CliUsageException(exception.getMessage(), exception);
    }
  }

  static CacheName cacheName(String value) {
    try {
      return new CacheName(value);
    } catch (IllegalArgumentException exception) {
      throw new CliUsageException(exception.getMessage(), exception);
    }
  }

  static Duration age(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int unitStart = unitStart(normalized);
    if (unitStart == 0) {
      throw usage("Invalid cache age: " + value);
    }
    long amount;
    try {
      amount = Long.parseLong(normalized.substring(0, unitStart));
    } catch (NumberFormatException exception) {
      throw new CliUsageException("Invalid cache age: " + value, exception);
    }
    try {
      return duration(amount, normalized.substring(unitStart).trim(), value);
    } catch (ArithmeticException exception) {
      throw new CliUsageException("Cache age is too large: " + value, exception);
    }
  }

  private static int unitStart(String value) {
    int index = 0;
    while (index < value.length() && Character.isDigit(value.charAt(index))) {
      index++;
    }
    return index;
  }

  private static Duration duration(long amount, String unit, String original) {
    return switch (unit) {
      case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
      case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
      case "d", "day", "days" -> Duration.ofDays(amount);
      default -> throw usage("Invalid cache age: " + original);
    };
  }

  static boolean hasJdbc(CliParser.RawArguments raw) {
    return raw.databaseType != null || raw.database != null || raw.host != null || raw.port != null
        || raw.user != null || raw.password != null || raw.jdbcUsername != null
        || raw.jdbcPassword != null || raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
  }

  static JdbcConnectionSpec jdbcConnection(CliParser.RawArguments raw) {
    boolean simpleIdentity = raw.databaseType != null || raw.database != null
        || raw.host != null || raw.port != null;
    boolean exactIdentity = raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
    validateJdbcForms(raw, simpleIdentity, exactIdentity);

    String username = raw.user != null ? raw.user : raw.jdbcUsername;
    String password = raw.password != null ? raw.password : raw.jdbcPassword;
    if (simpleIdentity) {
      return simpleConnection(raw, username, password);
    }
    reject(!exactIdentity,
        "database credentials require --jdbc-database or the --db/--database form");
    reject(raw.jdbcDatabase == null,
        "--jdbc-database is required with an exact JDBC driver hint");
    return new JdbcConnectionSpec(
        raw.jdbcDatabase, exactDriver(raw), credentials(username, password));
  }

  private static void validateJdbcForms(
      CliParser.RawArguments raw, boolean simpleIdentity, boolean exactIdentity) {
    reject(simpleIdentity && exactIdentity,
        "simple database options cannot be combined with exact JDBC options");
    reject(raw.user != null && raw.jdbcUsername != null,
        "--user and --jdbc-username are aliases and cannot be combined");
    reject(raw.password != null && raw.jdbcPassword != null,
        "--password and --jdbc-password are aliases and cannot be combined");
    reject(raw.jdbcDriver != null && raw.jdbcClassName != null,
        "--jdbc-driver and --jdbc-class-name are mutually exclusive");
  }

  private static JdbcConnectionSpec simpleConnection(
      CliParser.RawArguments raw, String username, String password) {
    reject((raw.databaseType == null) != (raw.database == null),
        "--db and --database must be supplied together");
    reject((raw.host != null || raw.port != null) && raw.databaseType == null,
        "--host and --port require --db");
    String driver = knownDriver(raw.databaseType);
    reject(driver.equals("h2") && (raw.host != null || raw.port != null),
        "--host and --port are not valid for H2");
    reject((driver.equals("hana") || driver.equals("informix")) && raw.port == null,
        "--port is required for " + driver);
    String resolvedUsername = username == null ? defaultUsername(driver) : username;
    return new JdbcConnectionSpec(
        jdbcUrl(driver, raw.database, raw.host, raw.port),
        new DriverSelection.Known(driver),
        credentials(resolvedUsername, password));
  }

  private static DriverSelection exactDriver(CliParser.RawArguments raw) {
    if (raw.jdbcClassName != null) {
      return new DriverSelection.ClassName(raw.jdbcClassName);
    }
    if (raw.jdbcDriver != null) {
      return new DriverSelection.Known(knownDriver(raw.jdbcDriver));
    }
    return new DriverSelection.Automatic();
  }

  private static Credentials credentials(String username, String password) {
    return new Credentials(credential(username), credential(password));
  }

  private static Credentials.Value credential(String value) {
    return value == null
        ? new Credentials.Value.Unspecified()
        : new Credentials.Value.Specified(value);
  }

  static String knownDriver(String value) {
    if (value == null || value.isBlank()) {
      throw usage("No database type supplied");
    }
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
    if (condition) {
      throw usage(message);
    }
  }

  private static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }
}
