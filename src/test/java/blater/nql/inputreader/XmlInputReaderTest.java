package blater.nql.inputreader;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void mapsXmlFileToHierarchyAndExpandsTemplateParameters() throws Exception {
    Path input = tempDir.resolve("input.xml");
    Files.writeString(input, """
        <message source="${source}">
          <person id="${person.id}">
            <firstname>${firstname}</firstname>
            <surname>Flintstone</surname>
            <nickname>Ace</nickname>
            <nickname>${nickname}</nickname>
          </person>
        </message>
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new XmlInputReader().load(input.toString(), Map.of(
        "source", "client",
        "person.id", "7",
        "firstname", "Fred",
        "nickname", "Freddy"));

    Node root = hierarchy.getRoot();
    assertEquals("message", root.getName());
    assertNull(root.getValue());

    Node source = child(root, "source");
    assertTrue(source.isAttribute());
    assertEquals("client", source.getValue());

    Node person = child(root, "person");
    assertFalse(person.isAttribute());

    Node id = child(person, "id");
    assertTrue(id.isAttribute());
    assertEquals("7", id.getValue());

    assertEquals("Fred", child(person, "firstname").getValue());
    assertEquals("Flintstone", child(person, "surname").getValue());
    assertEquals(List.of("Ace", "Freddy"), children(person, "nickname").stream()
        .map(Node::getValue)
        .toList());
  }

  @Test
  void emptyFilenameReturnsEmptyHierarchy() {
    Hierarchy hierarchy = new XmlInputReader().load("", Map.of());

    assertTrue(hierarchy.isEmpty());
  }

  @Test
  void emptyFileReturnsEmptyHierarchy() throws Exception {
    Path input = tempDir.resolve("empty.xml");
    Files.writeString(input, "", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new XmlInputReader().load(input.toString(), Map.of());

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
