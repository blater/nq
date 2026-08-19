package blater.nql.cli.parse;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validates named help topics and cache subtopics. */
final class CliHelpTopicValidator {
  private static final Set<String> NAMED_TOPICS = Set.of(
      "connection", "database", "db", "jdbc",
      "output", "report-format", "cache-dir", "cache-directory",
      "parameters", "parameter", "params", "config", "parquet", "query");
  private static final Set<String> CACHE_SUBTOPICS = Set.of("load", "use", "list", "clear");

  private CliHelpTopicValidator() {
  }

  static void validate(List<String> topic) {
    if (topic.size() > 2) {
      throw CliParser.usage("help accepts a command and optional subcommand");
    }
    if (topic.isEmpty()) {
      return;
    }
    String first = topic.getFirst().toLowerCase(Locale.ROOT);
    CliParser.Command command = CliParser.Command.from(first);
    boolean namedTopic = NAMED_TOPICS.contains(first);
    if ((command == null || command == CliParser.Command.IMPLICIT) && !namedTopic) {
      throw CliParser.usage("Unknown help topic: " + topic.getFirst());
    }
    if (topic.size() == 2 && !validCacheSubtopic(command, namedTopic, topic.get(1))) {
      throw CliParser.usage("Unknown help topic: " + String.join(" ", topic));
    }
  }

  private static boolean validCacheSubtopic(
      CliParser.Command command, boolean namedTopic, String subcommand) {
    return !namedTopic && command == CliParser.Command.CACHE
        && CACHE_SUBTOPICS.contains(subcommand.toLowerCase(Locale.ROOT));
  }
}
