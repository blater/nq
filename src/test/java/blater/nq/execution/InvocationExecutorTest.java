package blater.nq.execution;

import blater.nq.cli.parse.CliParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InvocationExecutorTest {
  @TempDir Path temporaryDirectory;
  private final CliParser parser = new CliParser();
  private final InvocationExecutor executor = new InvocationExecutor();

  @Test
  void convertsImplicitStandardInputThroughTypedDispatch() throws Exception {
    String output = capture(() -> executor.execute(
        parser.parse(), environment("{\"customer\":{\"id\":7}}")));

    assertTrue(output.contains("\"id\":\"7\""));
  }

  @Test
  void runsLiteralScriptAgainstLiteralDataThroughTemporaryDatabase() throws Exception {
    String output = capture(() -> executor.execute(
        parser.parse(
            "select id from customer;",
            "{\"customer\":[{\"id\":\"7\"}]}"),
        environment("")));

    assertTrue(output.contains("\"id\":\"7\""));
  }

  @Test
  void cacheLoadUsesLogicalNameAndFrozenReportEnvelope() throws Exception {
    Path input = Files.writeString(
        temporaryDirectory.resolve("customers.json"),
        "{\"customer\":[{\"id\":\"OLD\"}]}");
    Path cache = temporaryDirectory.resolve("cache");

    String output = capture(() -> executor.execute(
        parser.parse(
            "cache", "load", input.toString(), "customers",
            "--cache-dir", cache.toString(), "--report-format", "json"),
        environment("")));

    assertTrue(output.contains("\"schema_version\":1"));
    assertTrue(output.contains("\"command\":\"cache.load\""));
    assertTrue(output.contains("\"cache_name\":\"customers\""));
    assertTrue(Files.isRegularFile(cache.resolve("customers.mv.db")));
    assertTrue(Files.isRegularFile(cache.resolve(".active")));
  }

  @Test
  void dataSuppliedToCacheTargetedRunIsAvailableToScriptButNeverAutoLoaded() throws Exception {
    Path base = Files.writeString(
        temporaryDirectory.resolve("base.json"),
        "{\"customer\":[{\"id\":\"OLD\"}]}");
    Path additions = Files.writeString(
        temporaryDirectory.resolve("additions.json"),
        "{\"customer\":[{\"id\":\"NEW\"}]}");
    Path cache = temporaryDirectory.resolve("cache");
    capture(() -> executor.execute(
        parser.parse(
            "cache", "load", base.toString(), "customers",
            "--cache-dir", cache.toString()),
        environment("")));

    String output = capture(() -> executor.execute(
        parser.parse(
            "select id from customer;", additions.toString(), "--cache",
            "--name", "customers", "--cache-dir", cache.toString()),
        environment("")));

    assertTrue(output.contains("\"id\":\"OLD\""));
    assertTrue(!output.contains("\"id\":\"NEW\""));
  }

  @Test
  void catalogUsesTheFrozenOperationalReportEnvelope() throws Exception {
    Path input = Files.writeString(
        temporaryDirectory.resolve("catalog.json"),
        "{\"customer\":[{\"id\":\"7\"}]}");

    String output = capture(() -> executor.execute(
        parser.parse(
            "catalog", input.toString(), "*", "--report-format", "json"),
        environment("")));

    assertTrue(output.contains("\"schema_version\":1"));
    assertTrue(output.contains("\"status\":\"ok\""));
    assertTrue(output.contains("\"command\":\"catalog\""));
    assertTrue(output.contains("\"details\":{\"catalog\":"));
    assertTrue(output.contains("CUSTOMER"));
    assertTrue(output.contains("\"table\":["));
    assertTrue(output.contains("\"column\":["));
    assertTrue(output.contains("\"nullable\":true"));
  }

  private static InputEnvironment environment(String data) {
    InputStream input = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8));
    return new InputEnvironment() {
      @Override public InputStream stdin() { return input; }
      @Override public StdinDisposition stdinDisposition() { return StdinDisposition.REDIRECTED; }
      @Override public boolean hasImmediatelyAvailableInput() { return !data.isEmpty(); }
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
