package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsTomlInputReader() {
    assertInstanceOf(TomlInputReader.class, InputReader.of(InputType.TOML));
  }

  @Test
  void mapsTablesArraysAndScalarsThroughTheSharedStructuredDataMapper() throws Exception {
    Path input = tempDir.resolve("people.toml");
    Files.writeString(input, """
        title = "${title}"
        ports = [8000, 8001]

        [database]
        enabled = true

        [[people]]
        id = 1
        name = "Alice"

        [people.address]
        city = "London"

        [[people]]
        id = 2
        name = "Bob"
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new TomlInputReader().load(
        input.toString(),
        Map.of("title", "Directory"));

    Node root = hierarchy.getRoot();
    assertEquals("toml", root.getName());
    assertEquals("Directory", child(root, "title").getValue());
    assertEquals(
        List.of("8000", "8001"),
        children(root, "ports").stream().map(Node::getValue).toList());
    assertEquals("true", child(child(root, "database"), "enabled").getValue());
    assertEquals(2, children(root, "people").size());
    assertEquals("Alice", child(children(root, "people").getFirst(), "name").getValue());
    assertEquals(
        "London",
        child(child(children(root, "people").getFirst(), "address"), "city").getValue());
  }

  @Test
  void oneTopLevelTableBecomesTheNamedRootLikeYaml() throws Exception {
    Path input = tempDir.resolve("customer.toml");
    Files.writeString(input, """
        [customer]
        id = 7
        name = "Alice"
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new TomlInputReader().load(input.toString(), Map.of());

    assertEquals("customer", hierarchy.getRoot().getName());
    assertEquals("7", child(hierarchy.getRoot(), "id").getValue());
  }

  @Test
  void malformedInputFailsClearly() throws Exception {
    Path input = tempDir.resolve("broken.toml");
    Files.writeString(input, "value = [", StandardCharsets.UTF_8);

    IllegalStateException problem = assertThrows(
        IllegalStateException.class,
        () -> new TomlInputReader().load(input.toString(), Map.of()));

    assertTrue(problem.getMessage().contains("Malformed TOML input file"));
  }

  private Node child(Node parent, String name) {
    return children(parent, name).getFirst();
  }

  private List<Node> children(Node parent, String name) {
    return parent.getChildren().stream()
        .filter(candidate -> candidate.getName().equals(name))
        .toList();
  }
}
