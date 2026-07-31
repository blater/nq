package blater.nq.outputwriter.markdown;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.outputwriter.MarkdownOutputWriter;
import blater.nq.outputwriter.OutputType;
import blater.nq.outputwriter.OutputWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownOutputWriterTest {
  @Test
  void repeatedRootChildrenRenderAsRowsWithStableColumns() {
    Node root = new Node("people");
    Node alice = person("1", "A");
    Node address = new Node("address");
    address.addNode(valueNode("city", "L"));
    alice.addNode(address);
    root.addNode(alice);
    root.addNode(person("2", "B"));

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertEquals(4, output.lines().count());
    assertTrue(output.contains("A"));
    assertTrue(output.contains("L"));
    assertTrue(output.contains("B"));
  }

  @Test
  void anonymousRootRendersItsSingleChildAsARecord() {
    Node root = new Node("");
    root.addNode(person("1", "Alice"));

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertEquals(3, output.lines().count());
    assertTrue(output.contains("Alice"));
  }

  @Test
  void repeatedObjectsExpandIntoReadableRows() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "1"));
    root.addNode(phone("A"));
    root.addNode(phone("B"));

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertEquals(4, output.lines().count());
    assertTrue(output.contains("A"));
    assertTrue(output.contains("B"));
    assertFalse(output.contains("[{"));
  }

  @Test
  void catalogColumnsRenderAsRowsInsteadOfJson() {
    Node root = new Node("catalog");
    Node table = new Node("table");
    table.addNode(valueNode("name", "T"));
    table.addNode(valueNode("catalog", "C"));
    table.addNode(valueNode("schema", "S"));
    table.addNode(valueNode("type", "B"));
    Node columns = new Node("columns");
    columns.addNode(column("I", "V", "Y", "1"));
    columns.addNode(column("N", "V", "Y", "2"));
    table.addNode(columns);
    root.addNode(table);

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertEquals(4, output.lines().count());
    assertEquals(2, output.lines().filter(line -> line.contains("T")).count());
    assertTrue(output.contains("I"));
    assertTrue(output.contains("N"));
    assertFalse(output.contains("[{"));
  }

  @Test
  void attributesAndNullsRenderAsOrdinaryCells() {
    Node root = new Node("person");
    Node id = valueNode("id", "7");
    id.setAttribute(true);
    root.addNode(id);
    Node middleName = new Node("middleName");
    middleName.setNullValue(true);
    root.addNode(middleName);

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertEquals(3, output.lines().count());
    assertTrue(output.contains("7"));
  }

  @Test
  void rendersCellContentLiterallyWithoutEscapes() {
    Node root = new Node("message");
    root.addNode(valueNode("a", "_"));
    root.addNode(valueNode("b", "*"));
    root.addNode(valueNode("c", "<"));
    root.addNode(valueNode("d", "&"));
    root.addNode(valueNode("e", "["));
    root.addNode(valueNode("f", "\\"));

    String output = MarkdownOutputWriter.map(new Hierarchy(root));

    assertTrue(output.contains("_"));
    assertTrue(output.contains("*"));
    assertTrue(output.contains("<"));
    assertTrue(output.contains("&"));
    assertTrue(output.contains("["));
    assertTrue(output.contains("\\"));
    assertFalse(output.contains("\\_"));
    assertFalse(output.contains("\\*"));
    assertFalse(output.contains("&lt;"));
    assertFalse(output.contains("&amp;"));
    assertFalse(output.contains("\\["));
  }

  @Test
  void emptyHierarchyMapsAsEmptyString() {
    assertEquals("", MarkdownOutputWriter.map(null));
    assertEquals("", MarkdownOutputWriter.map(new Hierarchy()));
  }

  private Node person(String id, String name) {
    Node person = new Node("person");
    person.addNode(valueNode("id", id));
    person.addNode(valueNode("name", name));
    return person;
  }

  private Node phone(String number) {
    Node phone = new Node("phone");
    phone.addNode(valueNode("number", number));
    return phone;
  }

  private Node column(String name, String type, String nullable, String position) {
    Node column = new Node("column");
    column.addNode(valueNode("name", name));
    column.addNode(valueNode("type", type));
    column.addNode(valueNode("nullable", nullable));
    column.addNode(valueNode("position", position));
    return column;
  }

  private Node valueNode(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }

}
