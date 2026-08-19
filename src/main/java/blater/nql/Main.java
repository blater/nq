package blater.nql;

import blater.nql.execution.SystemInputEnvironment;

/** Process launcher: argv to typed invocation to execution to stable exit status. */
public final class Main {
  private Main() {
  }

  public static void main(String... arguments) {
    int status = new NqlApplication().run(arguments, new SystemInputEnvironment());
    if (status != NqlApplication.SUCCESS) {
      System.exit(status);
    }
  }
}
