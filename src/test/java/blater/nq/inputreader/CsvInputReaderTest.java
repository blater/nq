package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.HierarchyPath;
import blater.nq.domain.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsCsvInputReader() {
    assertInstanceOf(CsvInputReader.class, InputReader.of(InputType.CSV));
  }

  @Test
  void mapsSimpleCsvRowsToRepeatedItemHierarchyAndExpandsTemplates() throws Exception {
    Path input = tempDir.resolve("people.csv");
    Files.writeString(input, """
        firstname,surname
        ${firstname},Flintstone
        Wilma,Flintstone
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of("firstname", "Fred"));

    Node root = hierarchy.getRoot();
    assertEquals("csv", root.getName());
    List<Node> rows = children(root, "item");
    assertEquals(2, rows.size());
    assertFalse(rows.get(0).isArrayItem());
    assertFalse(child(rows.get(0), "firstname").isArrayItem());
    assertEquals("Fred", child(rows.get(0), "firstname").getValue());
    assertEquals("Wilma", child(rows.get(1), "firstname").getValue());
    assertEquals(List.of("Fred", "Wilma"), hierarchy.select(HierarchyPath.fromSlashPath("/csv/item/firstname"))
        .stream()
        .map(Node::getValue)
        .toList());
  }

  @Test
  void dottedHeadersCreateNestedHierarchyUnderRows() throws Exception {
    Path input = tempDir.resolve("nested.csv");
    Files.writeString(input, """
        person.id,person.firstname,address.city
        7,Fred,Bedrock
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
    Node row = child(hierarchy.getRoot(), "item");

    assertEquals("7", child(child(row, "person"), "id").getValue());
    assertEquals("Fred", child(child(row, "person"), "firstname").getValue());
    assertEquals("Bedrock", child(child(row, "address"), "city").getValue());
  }

  @Test
  void quotedCommasQuotesAndNewlinesParseAsOneCell() throws Exception {
    Path input = tempDir.resolve("quoted.csv");
    Files.writeString(input, "name,note\nFred,\"hello, \"\"friend\"\"\nagain\"\n", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
    Node row = child(hierarchy.getRoot(), "item");

    assertEquals("Fred", child(row, "name").getValue());
    assertEquals("hello, \"friend\"\nagain", child(row, "note").getValue());
  }

  @Test
  void missingTrailingCellMapsToEmptyString() throws Exception {
    Path input = tempDir.resolve("missing.csv");
    Files.writeString(input, "firstname,surname\nFred\n", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
    Node row = child(hierarchy.getRoot(), "item");

    assertEquals("", child(row, "surname").getValue());
  }

  @Test
  void emptyFilenameReturnsEmptyHierarchy() {
    Hierarchy hierarchy = new CsvInputReader().load("", Map.of());

    assertTrue(hierarchy.isEmpty());
  }

  @Test
  void emptyFileReturnsEmptyHierarchy() throws Exception {
    Path input = tempDir.resolve("empty.csv");
    Files.writeString(input, "", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());

    assertTrue(hierarchy.isEmpty());
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
