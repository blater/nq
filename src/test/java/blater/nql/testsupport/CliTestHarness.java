package blater.nql.testsupport;

import blater.nql.NqlApplication;
import blater.nql.execution.InputEnvironment;
import blater.nql.execution.StdinDisposition;

import java.io.InputStream;

/** Runs the real application boundary without terminating the shared test JVM. */
public final class CliTestHarness {
  private CliTestHarness() {
  }

  public static void run(String... arguments) {
    runWithDisposition(StdinDisposition.TERMINAL, arguments);
  }

  public static void runWithRedirectedInput(String... arguments) {
    runWithDisposition(StdinDisposition.REDIRECTED, arguments);
  }

  private static void runWithDisposition(
      StdinDisposition disposition,
      String... arguments) {
    int status = new NqlApplication().run(arguments, new InputEnvironment() {
      @Override
      public InputStream stdin() {
        return System.in;
      }

      @Override
      public StdinDisposition stdinDisposition() {
        return disposition;
      }

      @Override
      public boolean hasImmediatelyAvailableInput() {
        return false;
      }
    });
    if (status != NqlApplication.SUCCESS) {
      throw new AssertionError("nql exited with status " + status);
    }
  }
}
