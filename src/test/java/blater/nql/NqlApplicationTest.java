package blater.nql;

import blater.nql.cli.parse.CliParser;
import blater.nql.execution.InputEnvironment;
import blater.nql.execution.InvocationExecutor;
import blater.nql.execution.StdinDisposition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NqlApplicationTest {
  @Test
  void usageFailureReturnsTwoAndEmitsOneStableDiagnostic() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    var application = new NqlApplication(
        new CliParser(), new InvocationExecutor(), new PrintStream(errors));

    int result = application.run(
        new String[] {"convert", "data.json", "--output=YAML"}, environment());

    assertEquals(NqlApplication.USAGE_FAILURE, result);
    assertTrue(errors.toString().contains("ERROR [NQL-CLI-USAGE]"));
  }

  @Test
  void executionFailureReturnsOneWithoutAStackTrace() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    var application = new NqlApplication(
        new CliParser(), new InvocationExecutor(), new PrintStream(errors));

    int result = application.run(
        new String[] {"convert", "missing.json"}, environment());

    assertEquals(NqlApplication.EXECUTION_FAILURE, result);
    assertTrue(errors.toString().contains("ERROR [NQL-EXECUTION-FAILED]"));
    assertTrue(!errors.toString().contains("\tat "));
  }

  @Test
  void explicitJsonReportFormatProducesOneStructuredDiagnosticEnvelope() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    var application = new NqlApplication(
        new CliParser(), new InvocationExecutor(), new PrintStream(errors));

    int result = application.run(
        new String[] {"catalog", "--bogus", "--report-format", "json"}, environment());

    assertEquals(NqlApplication.USAGE_FAILURE, result);
    String diagnostic = errors.toString();
    assertTrue(diagnostic.startsWith("{\"schema_version\":1"));
    assertTrue(diagnostic.contains("\"code\":\"NQL-CLI-USAGE\""));
    assertTrue(diagnostic.contains("\"level\":\"error\""));
    assertTrue(!diagnostic.contains("\"report\":"));
    assertEquals(1, diagnostic.lines().count());
  }

  @Test
  void engineFatalBecomesStructuredExecutionFailureRatherThanExiting() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    var application = new NqlApplication(
        new CliParser(), new InvocationExecutor(), new PrintStream(errors));

    int result = application.run(new String[] {
        "catalog", "--input-text", "{\"broken\"", "--report-format", "json"
    }, environment());

    assertEquals(NqlApplication.EXECUTION_FAILURE, result);
    String diagnostic = errors.toString();
    assertTrue(diagnostic.contains("\"code\":\"NQL-EXECUTION-FAILED\""));
    assertEquals(1, diagnostic.lines().count());
  }

  @Test
  void explicitSourceAndVisibleStdinProduceStructuredWarningWithoutFailing() {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    var application = new NqlApplication(
        new CliParser(), new InvocationExecutor(), new PrintStream(errors));

    int result;
    PrintStream originalOutput = System.out;
    try (PrintStream ignored = new PrintStream(OutputStream.nullOutputStream())) {
      System.setOut(ignored);
      result = application.run(new String[] {
          "catalog", "--input-text", "{\"customer\":{\"id\":1}}",
          "--report-format", "json"
      }, environment(true));
    } finally {
      System.setOut(originalOutput);
    }

    assertEquals(NqlApplication.SUCCESS, result);
    String diagnostic = errors.toString();
    assertTrue(diagnostic.contains("\"code\":\"NQL-RUNTIME-WARNING\""));
    assertTrue(diagnostic.contains("Ignoring standard input"));
    assertEquals(1, diagnostic.lines().count());
  }

  private static InputEnvironment environment() {
    return environment(false);
  }

  private static InputEnvironment environment(boolean inputAvailable) {
    return new InputEnvironment() {
      @Override public InputStream stdin() { return new ByteArrayInputStream(new byte[0]); }
      @Override public StdinDisposition stdinDisposition() { return StdinDisposition.TERMINAL; }
      @Override public boolean hasImmediatelyAvailableInput() { return inputAvailable; }
    };
  }
}
