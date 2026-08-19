package blater.nql.inputreader;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.domain.ScalarKind;
import blater.nql.outputwriter.JsonOutputWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifficultJsonStructureTest {
  @TempDir
  Path tempDir;

  @Test
  void preservesArraysOfArraysAndEmptyContainers() throws Exception {
    String json = """
        {"matrix":[[1,2],[],[[3],[]]]}
        """.strip();

    Hierarchy hierarchy = load(json);
    Node matrix = hierarchy.getRoot();
    assertTrue(matrix.isCollection());
    assertEquals(3, matrix.getChildren().size());

    Node firstRow = matrix.getChildren().get(0);
    assertTrue(firstRow.isCollection());
    assertEquals(List.of("1", "2"), firstRow.getChildren().stream().map(Node::getValue).toList());

    Node emptyRow = matrix.getChildren().get(1);
    assertTrue(emptyRow.isCollection());
    assertTrue(emptyRow.getChildren().isEmpty());

    Node nestedRow = matrix.getChildren().get(2);
    assertTrue(nestedRow.isCollection());
    assertEquals(2, nestedRow.getChildren().size());
    assertTrue(nestedRow.getChildren().get(0).isCollection());
    assertTrue(nestedRow.getChildren().get(1).isCollection());

    assertEquals(json, JsonOutputWriter.map(hierarchy));
  }

  @Test
  void preservesHeterogeneousArrayOrderAndContainerKinds() throws Exception {
    String json = """
        {"values":[null,true,42,"text",{},[],{"nested":[]},[false]]}
        """.strip();

    Hierarchy hierarchy = load(json);
    List<Node> values = hierarchy.getRoot().getChildren();
    assertEquals(8, values.size());
    assertTrue(values.get(0).isNull());
    assertEquals(ScalarKind.BOOLEAN, values.get(1).getScalarKind());
    assertEquals(ScalarKind.NUMBER, values.get(2).getScalarKind());
    assertEquals(ScalarKind.STRING, values.get(3).getScalarKind());
    assertFalse(values.get(4).isCollection(), "empty object");
    assertTrue(values.get(5).isCollection(), "empty array");
    assertTrue(child(values.get(6), "nested").isCollection());
    assertTrue(values.get(7).isCollection());

    assertEquals(json, JsonOutputWriter.map(hierarchy));
  }

  @Test
  void preservesRaggedObjectsWithMissingAndDifferentlyShapedBranches() throws Exception {
    String json = """
        {"record":[{"id":1},{"id":2,"profile":{}},{"id":3,"profile":{"tags":[]}},{"id":4,"profile":{"tags":[{"code":"a"},{}]}}]}
        """.strip();

    Hierarchy hierarchy = load(json);
    List<Node> records = hierarchy.getRoot().getChildren();
    assertEquals(4, records.size());
    assertTrue(children(records.get(0), "profile").isEmpty());
    assertTrue(child(records.get(1), "profile").getChildren().isEmpty());
    assertTrue(child(child(records.get(2), "profile"), "tags").isCollection());
    assertEquals(2, child(records.get(3), "profile").getChildren().stream()
        .filter(node -> node.getName().equals("tags"))
        .count());

    assertEquals(json, JsonOutputWriter.map(hierarchy));
  }

  @Test
  void handlesDeepAlternatingObjectAndArrayNesting() throws Exception {
    int depth = 48;
    String json = "0";
    for (int level = depth - 1; level >= 0; level--) {
      json = level % 2 == 0
          ? "{\"level" + level + "\":" + json + "}"
          : "[" + json + "]";
    }

    Hierarchy hierarchy = load(json);
    Node root = hierarchy.getRoot();
    assertEquals("level0", root.getName());
    assertTrue(root.isCollection());

    Node current = child(root.getChildren().getFirst(), "level2");
    for (int level = 2; level < depth; level += 2) {
      assertEquals("level" + level, current.getName());
      assertTrue(current.isArrayItem());
      if (level + 2 < depth) {
        current = child(current, "level" + (level + 2));
      }
    }
    assertEquals("0", current.getValue());
    assertEquals(json, JsonOutputWriter.map(hierarchy));
  }

  @Test
  void representsEveryValidRootShape() throws Exception {
    Hierarchy object = load("{}");
    assertEquals(Hierarchy.RootKind.SYNTHETIC_OBJECT, object.getRootKind());
    assertFalse(object.getRoot().isCollection());
    assertEquals("{}", JsonOutputWriter.map(object));

    Hierarchy array = load("[]");
    assertEquals(Hierarchy.RootKind.SYNTHETIC_ARRAY, array.getRootKind());
    assertTrue(array.getRoot().isCollection());
    assertEquals("[]", JsonOutputWriter.map(array));

    Hierarchy scalar = load("true");
    assertEquals(Hierarchy.RootKind.NAMED, scalar.getRootKind());
    assertEquals("json", scalar.getRoot().getName());
    assertEquals("true", scalar.getRoot().getValue());
    assertEquals("{\"json\":true}", JsonOutputWriter.map(scalar));
  }

  private Hierarchy load(String json) throws Exception {
    Path input = tempDir.resolve("structure-" + Math.abs(json.hashCode()) + ".json");
    Files.writeString(input, json, StandardCharsets.UTF_8);
    return new JsonInputReader().load(input.toString(), Map.of());
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
