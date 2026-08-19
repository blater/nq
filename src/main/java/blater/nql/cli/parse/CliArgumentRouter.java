package blater.nql.cli.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Locates explicit command and cache-subcommand tokens before option parsing. */
final class CliArgumentRouter {
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
      "--capabilities", "capabilities",
      "--all", "all",
      "--debug", "debug",
      "--no-key-inference", "no-key-inference",
      "-h", "help",
      "--help", "help",
      "--version", "version");
  private static final Set<String> CACHE_SUBCOMMANDS = Set.of("load", "use", "list", "clear");

  private CliArgumentRouter() {
  }

  static Route route(String[] arguments) {
    CliSyntax.validate(arguments, VALUE_OPTIONS, BOOLEAN_OPTIONS);
    List<String> remaining = new ArrayList<>(List.of(arguments));
    int commandIndex = firstPositionalBeforeDelimiter(remaining);
    if (commandIndex < 0) {
      return new Route(CliParser.Command.IMPLICIT, null, remaining);
    }
    CliParser.Command command = CliParser.Command.from(remaining.get(commandIndex));
    if (command == null) {
      return new Route(CliParser.Command.IMPLICIT, null, remaining);
    }
    remaining.remove(commandIndex);
    return command == CliParser.Command.CACHE
        ? cacheRoute(remaining)
        : new Route(command, null, remaining);
  }

  private static Route cacheRoute(List<String> remaining) {
    int subcommandIndex = firstPositionalBeforeDelimiter(remaining);
    if (subcommandIndex < 0 && CliSyntax.containsHelpFlag(remaining)) {
      return new Route(CliParser.Command.CACHE, null, remaining);
    }
    if (subcommandIndex < 0) {
      throw CliParser.usage("cache requires a subcommand: load, use, list, or clear");
    }
    String subcommand = remaining.remove(subcommandIndex).toLowerCase(Locale.ROOT);
    if (!CACHE_SUBCOMMANDS.contains(subcommand)) {
      throw CliParser.usage("Unknown cache subcommand: " + subcommand);
    }
    return new Route(CliParser.Command.CACHE, subcommand, remaining);
  }

  private static int firstPositionalBeforeDelimiter(List<String> arguments) {
    for (int index = 0; index < arguments.size(); index++) {
      String token = arguments.get(index);
      if ("--".equals(token)) {
        return -1;
      }
      if (VALUE_OPTIONS.contains(token)) {
        index++;
      } else if (!token.startsWith("-")) {
        return index;
      }
    }
    return -1;
  }

  record Route(CliParser.Command command, String subcommand, List<String> remaining) {
  }
}
