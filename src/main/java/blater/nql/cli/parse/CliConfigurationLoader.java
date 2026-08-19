package blater.nql.cli.parse;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Applies operational configuration and environment defaults to parsed CLI arguments. */
final class CliConfigurationLoader {
  private static final Set<String> SUPPORTED_KEYS = Set.of(
      "cache.dir", "jdbc.database", "jdbc.driver", "jdbc.class.name",
      "jdbc.username", "jdbc.password");

  private final Supplier<Map<String, String>> environment;

  CliConfigurationLoader(Supplier<Map<String, String>> environment) {
    this.environment = environment;
  }

  void apply(CliParser.Command command, CliParser.RawArguments raw) {
    CliParser.Command effective = command == CliParser.Command.IMPLICIT
        ? CliImplicitBinder.implicitCommand(raw)
        : command;
    if (raw.config != null && !supportsConfiguration(effective)) {
      throw usage("--config is only valid for run, catalog, and cache commands");
    }

    Map<String, String> config = raw.config == null
        ? Map.of()
        : operationalConfig(Path.of(raw.config));
    applyCacheDirectory(raw, config);
    if (effective == CliParser.Command.CACHE || raw.cache) {
      return;
    }
    applyJdbcConfiguration(raw, config);
  }

  private static boolean supportsConfiguration(CliParser.Command command) {
    return command != CliParser.Command.CONVERT
        && command != CliParser.Command.HELP
        && command != CliParser.Command.VERSION;
  }

  private void applyCacheDirectory(CliParser.RawArguments raw, Map<String, String> config) {
    if (raw.cacheDirectory != null) {
      return;
    }
    String environmentValue = environment.get().get("NQL_CACHE_DIR");
    raw.cacheDirectory = environmentValue != null && !environmentValue.isBlank()
        ? environmentValue
        : config.get("cache.dir");
  }

  private static void applyJdbcConfiguration(
      CliParser.RawArguments raw, Map<String, String> config) {
    boolean cliSimpleIdentity = raw.databaseType != null || raw.database != null
        || raw.host != null || raw.port != null;
    boolean cliExactIdentity = raw.jdbcDatabase != null || raw.jdbcDriver != null
        || raw.jdbcClassName != null;
    if (cliSimpleIdentity && cliExactIdentity) {
      throw usage("simple database options cannot be combined with exact JDBC options");
    }
    if (!cliSimpleIdentity) {
      applyExactIdentity(raw, config);
    }
    if (cliSimpleIdentity || hasExactIdentity(raw)) {
      applyCredentials(raw, config);
    }
  }

  private static void applyExactIdentity(
      CliParser.RawArguments raw, Map<String, String> config) {
    if (raw.jdbcDatabase == null) {
      raw.jdbcDatabase = config.get("jdbc.database");
    }
    if (raw.jdbcDriver == null && raw.jdbcClassName == null) {
      raw.jdbcDriver = config.get("jdbc.driver");
      raw.jdbcClassName = config.get("jdbc.class.name");
    }
  }

  private static boolean hasExactIdentity(CliParser.RawArguments raw) {
    return raw.jdbcDatabase != null || raw.jdbcDriver != null || raw.jdbcClassName != null;
  }

  private static void applyCredentials(
      CliParser.RawArguments raw, Map<String, String> config) {
    if (raw.user == null && raw.jdbcUsername == null) {
      raw.jdbcUsername = config.get("jdbc.username");
    }
    if (raw.password == null && raw.jdbcPassword == null) {
      raw.jdbcPassword = config.get("jdbc.password");
    }
  }

  private static Map<String, String> operationalConfig(Path path) {
    Map<String, String> values = CliPropertyFiles.read(path, "config");
    values.keySet().stream()
        .filter(key -> !SUPPORTED_KEYS.contains(key))
        .findFirst()
        .ifPresent(key -> {
          throw usage("Unsupported config key: " + key);
        });
    if (values.containsKey("jdbc.driver") && values.containsKey("jdbc.class.name")) {
      throw usage("config keys jdbc.driver and jdbc.class.name are mutually exclusive");
    }
    return values;
  }

  private static CliUsageException usage(String message) {
    return new CliUsageException(message);
  }
}
