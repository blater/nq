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

class JsonLinesInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsJsonLinesInputReader() {
    assertInstanceOf(JsonLinesInputReader.class, InputReader.of(InputType.JSONL));
  }

  @Test
  void mapsNonBlankLinesAsTopLevelArrayRecords() throws Exception {
    Path input = tempDir.resolve("people.jsonl");
    Files.writeString(input, """
        {"id": 7, "name": "${firstname}", "active": true}

        {"id": 8, "name": "Wilma", "middleName": null}
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new JsonLinesInputReader().load(
        input.toString(),
        Map.of("firstname", "Fred"));

    Node root = hierarchy.getRoot();
    assertEquals("json", root.getName());
    List<Node> records = children(root, "item");
    assertEquals(2, records.size());
    assertTrue(records.stream().allMatch(Node::isArrayItem));
    assertEquals("7", child(records.get(0), "id").getValue());
    assertEquals("Fred", child(records.get(0), "name").getValue());
    assertEquals("true", child(records.get(0), "active").getValue());
    assertEquals("8", child(records.get(1), "id").getValue());
    assertTrue(child(records.get(1), "middleName").isNull());
  }

  @Test
  void hasTheSameHierarchyShapeAsAJsonTopLevelArray() throws Exception {
    Path jsonLines = tempDir.resolve("records.jsonl");
    Files.writeString(jsonLines, """
        {"id": 1, "tags": ["a", "b"]}
        {"id": 2, "nested": {"value": "x"}}
        """, StandardCharsets.UTF_8);
    Path json = tempDir.resolve("records.json");
    Files.writeString(json, """
        [
          {"id": 1, "tags": ["a", "b"]},
          {"id": 2, "nested": {"value": "x"}}
        ]
        """, StandardCharsets.UTF_8);

    Hierarchy linesHierarchy = new JsonLinesInputReader().load(jsonLines.toString(), Map.of());
    Hierarchy jsonHierarchy = new JsonInputReader().load(json.toString(), Map.of());

    assertEquals(jsonHierarchy.getRootKind(), linesHierarchy.getRootKind());
    assertEquals(jsonHierarchy.getRoot(), linesHierarchy.getRoot());
  }

  @Test
  void emptyAndBlankOnlyFilesProduceAnEmptyTopLevelArray() throws Exception {
    Path input = tempDir.resolve("empty.jsonl");
    Files.writeString(input, "\n  \r\n\t\n", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new JsonLinesInputReader().load(input.toString(), Map.of());

    assertEquals("json", hierarchy.getRoot().getName());
    assertTrue(hierarchy.getRoot().getChildren().isEmpty());
  }

  @Test
  void malformedRecordIdentifiesItsLine() throws Exception {
    Path input = tempDir.resolve("malformed.jsonl");
    Files.writeString(input, """
        {"id": 1}

        {"id":
        """, StandardCharsets.UTF_8);

    IllegalStateException error = assertThrows(
        IllegalStateException.class,
        () -> new JsonLinesInputReader().load(input.toString(), Map.of()));

    assertTrue(error.getMessage().contains(input.toString()));
    assertTrue(error.getMessage().contains("line 3"));
  }

  private Node child(Node parent, String name) {
    return children(parent, name).getFirst();
  }

  private List<Node> children(Node parent, String name) {
    return parent.getChildren().stream()
        .filter(child -> child.getName().equals(name))
        .toList();
  }
}
