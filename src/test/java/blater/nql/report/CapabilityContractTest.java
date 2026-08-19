package blater.nql.report;

import blater.nql.cli.parse.CliParser;
import blater.nql.execution.InputEnvironment;
import blater.nql.execution.InvocationExecutor;
import blater.nql.execution.StdinDisposition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractTest {
  @Test
  void contractContainsStableDiscoverySections() {
    Map<String, ?> details = CapabilityContract.details();

    assertEquals(1, details.get("contract_version"));
    assertTrue(details.get("nql_version") instanceof String);
    assertTrue(details.get("commands") instanceof List<?>);
    assertTrue(details.get("option_applicability") instanceof Map<?, ?>);
    assertTrue(details.get("formats") instanceof Map<?, ?>);
    assertTrue(details.get("jdbc_drivers") instanceof List<?>);
    assertTrue(details.get("stdin") instanceof Map<?, ?>);
    assertTrue(details.get("cache") instanceof Map<?, ?>);
    assertTrue(details.get("reports") instanceof Map<?, ?>);
    assertTrue(details.get("exit_codes") instanceof Map<?, ?>);
  }

  @Test
  void executionUsesReportEnvelopeAndNeverInspectsInputEnvironment() throws Exception {
    String commandOutput = capture(() -> new InvocationExecutor().execute(
        new CliParser().parse("capabilities"), inaccessibleEnvironment()));
    String flagOutput = capture(() -> new InvocationExecutor().execute(
        new CliParser().parse("--capabilities"), inaccessibleEnvironment()));

    assertEquals(commandOutput, flagOutput);
    assertTrue(commandOutput.startsWith(
        "{\"schema_version\":1,\"status\":\"ok\",\"command\":\"capabilities\","));
    assertTrue(commandOutput.contains("\"contract_version\":1"));
    assertTrue(commandOutput.contains("\"bare_invocation\":\"brief-help\""));
    assertTrue(commandOutput.contains("\"generated_identity_column\":\"_nql_id\""));
    assertEquals(1, commandOutput.lines().count());
  }

  @Test
  void yamlReportIsAvailable() throws Exception {
    String output = capture(() -> new InvocationExecutor().execute(
        new CliParser().parse("--capabilities", "-r", "yaml"),
        inaccessibleEnvironment()));

    assertTrue(output.startsWith(
        "schema_version: 1\nstatus: \"ok\"\ncommand: \"capabilities\"\n"));
    assertTrue(output.contains("contract_version: 1"));
  }

  private static InputEnvironment inaccessibleEnvironment() {
    return new InputEnvironment() {
      @Override public InputStream stdin() { throw new AssertionError("stdin inspected"); }
      @Override public StdinDisposition stdinDisposition() {
        throw new AssertionError("terminal state inspected");
      }
      @Override public boolean hasImmediatelyAvailableInput() {
        throw new AssertionError("stdin availability inspected");
      }
    };
  }

  private static String capture(ThrowingAction action) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      action.run();
    } finally {
      System.setOut(original);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
