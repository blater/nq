package blater.nq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConversionCliTest {
  @TempDir
  Path tempDir;

  @Test
  void xmlConvertsDirectlyToJsonByDefault() throws Exception {
    Path input = write("customer.xml", """
        <customer id="7"><name>Alice</name></customer>
        """);

    String output = captureStdout(() -> Main.main(input.toString()));

    assertEquals("""
        {"customer":{"id":"7","name":"Alice"}}
        """, output);
  }

  @Test
  void jsonConvertsDirectlyToYaml() throws Exception {
    Path input = write("customer.json", """
        {"customer":{"id":7,"name":"Alice"}}
        """);

    String output = captureStdout(() -> Main.main(input.toString(), "-o", "yaml"));

    assertEquals("""
        customer:
          id: "7"
          name: "Alice"

        """, output);
  }

  @Test
  void jsonConvertsDirectlyToXml() throws Exception {
    Path input = write("customer.json", """
        {"customer":{"id":7,"name":"Alice"}}
        """);

    String output = captureStdout(() -> Main.main(input.toString(), "--output", "xml"));

    assertTrue(output.contains("<customer>"));
    assertTrue(output.contains("<id>7</id>"));
    assertTrue(output.contains("<name>Alice</name>"));
  }

  @Test
  void csvConvertsDirectlyToMarkdown() throws Exception {
    Path input = write("customers.csv", """
        id,name
        1,Alice
        2,Bob
        """);

    String output = captureStdout(() -> Main.main(input.toString(), "--output", "markdown"));

    assertEquals(4, output.lines().count());
    assertTrue(output.contains("id"));
    assertTrue(output.contains("name"));
    assertTrue(output.contains("Alice"));
    assertTrue(output.contains("Bob"));
  }

  @Test
  void inlineScriptSuppressesDirectConversion() throws Exception {
    Path input = write("customer.json", """
        {"customer":{"id":7,"name":"Alice"}}
        """);

    String output = captureStdout(() -> Main.main(
        input.toString(),
        "select id into {result.id} from customer;",
        "--output", "json"));

    assertEquals("""
        [{"result":{"id":"7"}}]
        """, output);
    assertFalse(output.contains("Alice"));
  }

  @Test
  void typedStandardInputCanBeConvertedWithoutAQuery() throws Exception {
    InputStream original = System.in;
    try (InputStream input = new ByteArrayInputStream(
        "customer:\n  id: 7\n  name: Alice\n".getBytes(StandardCharsets.UTF_8))) {
      System.setIn(input);
      String output = captureStdout(() -> Main.main("-i", "yaml", "-o", "json"));
      assertEquals("""
          {"customer":{"id":"7","name":"Alice"}}
          """, output);
    } finally {
      System.setIn(original);
    }
  }

  private Path write(String name, String content) throws Exception {
    return Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
  }

  private String captureStdout(ThrowingRunnable runnable) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      runnable.run();
    } finally {
      System.setOut(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
