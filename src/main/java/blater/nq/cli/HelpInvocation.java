package blater.nq.cli;

import java.util.List;
import java.util.Objects;

/** Global, command, or subcommand help. */
public record HelpInvocation(List<String> topic, boolean brief) implements NqInvocation {
  public HelpInvocation {
    Objects.requireNonNull(topic, "topic");
    topic = List.copyOf(topic);
  }
}
