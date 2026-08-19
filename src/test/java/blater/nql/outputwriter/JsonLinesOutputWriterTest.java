package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonLinesOutputWriterTest {
  @Test
  void mapsEachSyntheticArrayItemAsOneJsonLine() {
    Node root = new Node("");
    root.addNode(row("1", "quote \" slash \\ newline\n"));
    root.addNode(row("2", null));

    assertEquals(
        List.of(
            "{\"id\":\"1\",\"value\":\"quote \\\" slash \\\\ newline\\n\"}",
            "{\"id\":\"2\",\"value\":null}"),
        JsonOutputWriter.mapLines(new Hierarchy(root)));
  }

  @Test
  void preservesNamedChildWrapperInSyntheticArrays() {
    Node root = new Node("");
    Node person = new Node("person");
    person.addNode(valueNode("id", "7"));
    root.addNode(person);

    assertEquals(
        List.of("{\"person\":{\"id\":\"7\"}}"),
        JsonOutputWriter.mapLines(new Hierarchy(root)));
  }

  @Test
  void mapsNonArrayHierarchyAsOneCompleteJsonLine() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "7"));
    Hierarchy hierarchy = new Hierarchy(root);

    assertEquals(List.of(JsonOutputWriter.map(hierarchy)), JsonOutputWriter.mapLines(hierarchy));
  }

  @Test
  void mapsEmptyHierarchyAndEmptySyntheticArrayAsNoLines() {
    assertEquals(List.of(), JsonOutputWriter.mapLines(new Hierarchy()));
    assertEquals(List.of(), JsonOutputWriter.mapLines(new Hierarchy(new Node(""))));
  }

  private Node row(String id, String value) {
    Node row = new Node("");
    row.addNode(valueNode("id", id));
    Node valueNode = valueNode("value", value);
    if (value == null) {
      valueNode.setNullValue(true);
    }
    row.addNode(valueNode);
    return row;
  }

  private Node valueNode(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }
}
