package blater.nq.execution;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** The production {@link InputEnvironment} backed by the current process. */
public final class SystemInputEnvironment implements InputEnvironment {
  private final InputStream stdin;
  private final TerminalDetector terminalDetector;

  public SystemInputEnvironment() {
    this(System.in, PlatformTerminalDetector.forCurrentPlatform());
  }

  SystemInputEnvironment(InputStream stdin, TerminalDetector terminalDetector) {
    this.stdin = Objects.requireNonNull(stdin, "stdin");
    this.terminalDetector = Objects.requireNonNull(terminalDetector, "terminalDetector");
  }

  @Override
  public InputStream stdin() {
    return stdin;
  }

  @Override
  public StdinDisposition stdinDisposition() {
    return terminalDetector.detect();
  }

  @Override
  public boolean hasImmediatelyAvailableInput() {
    try {
      return stdin.available() > 0;
    } catch (IOException ignored) {
      return false;
    }
  }
}
