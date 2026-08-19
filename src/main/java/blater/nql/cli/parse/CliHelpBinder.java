package blater.nql.cli.parse;

import blater.nql.cli.HelpInvocation;

import java.util.List;
import java.util.Locale;

/** Resolves explicit and option-triggered help topics. */
final class CliHelpBinder {
  private CliHelpBinder() {
  }

  static HelpInvocation bind(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    CliOptionValidator.validateHelpOptions(command, subcommand, raw);
    if (command == CliParser.Command.HELP) {
      List<String> topic = raw.positionals.stream()
          .map(value -> value.toLowerCase(Locale.ROOT))
          .toList();
      if (topic.size() > 2) {
        throw CliParser.usage("help accepts a command and optional subcommand");
      }
      return new HelpInvocation(topic, false);
    }
    if (raw.briefHelp) {
      return new HelpInvocation(List.of(), true);
    }
    return new HelpInvocation(topic(command, subcommand, raw), false);
  }

  private static List<String> topic(
      CliParser.Command command, String subcommand, CliParser.RawArguments raw) {
    return switch (command) {
      case RUN -> List.of("run");
      case CONVERT -> List.of("convert");
      case CATALOG -> List.of("catalog");
      case CACHE -> subcommand == null ? List.of("cache") : List.of("cache", subcommand);
      case CAPABILITIES -> List.of("capabilities");
      case VERSION -> List.of("version");
      case IMPLICIT -> implicitTopic(raw);
      case HELP -> List.of();
    };
  }

  private static List<String> implicitTopic(CliParser.RawArguments raw) {
    if (raw.inputFile != null || raw.inputText != null
        || raw.positionals.size() == 1
        && CliParser.isDataFilename(raw.positionals.getFirst())) {
      return List.of("convert");
    }
    if (raw.scriptFile != null || raw.scriptText != null
        || raw.positionals.stream().anyMatch(CliParser::isScriptFilename)) {
      return List.of("run");
    }
    return List.of();
  }
}
