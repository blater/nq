package blater.nq;

import blater.nq.execution.SystemInputEnvironment;

/** Process launcher: argv to typed invocation to execution to stable exit status. */
public final class Main {
  private Main() {
  }

  public static void main(String... arguments) {
    int status = new NqApplication().run(arguments, new SystemInputEnvironment());
    if (status != NqApplication.SUCCESS) {
      System.exit(status);
    }
  }
}
