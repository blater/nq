package blater.nql.testsupport;

import blater.nql.NqlApplication;
import blater.nql.execution.InputEnvironment;
import blater.nql.execution.StdinDisposition;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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

  public static synchronized String captureStdout(Runnable action) {
    PrintStream originalOut = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      action.run();
    } finally {
      System.setOut(originalOut);
    }
    return buffer.toString(StandardCharsets.UTF_8);
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
