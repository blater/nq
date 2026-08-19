package blater.nql.cli;

import java.util.List;
import java.util.Objects;

/** Global, command, or subcommand help. */
public record HelpInvocation(List<String> topic, boolean brief) implements NqlInvocation {
  public HelpInvocation {
    Objects.requireNonNull(topic, "topic");
    topic = List.copyOf(topic);
  }
}
