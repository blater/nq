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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsYamlInputReader() {
    assertInstanceOf(YamlInputReader.class, InputReader.of(InputType.YAML));
  }

  @Test
  void mapsSimpleYamlFileToHierarchyAndExpandsTemplateParameters() throws Exception {
    Path input = tempDir.resolve("input.yaml");
    Files.writeString(input, """
        message:
          person:
            id: ${person.id}
            firstname: ${firstname}
            active: true
            age: 42
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new YamlInputReader().load(input.toString(), Map.of(
        "person.id", "7",
        "firstname", "Fred"));

    Node root = hierarchy.getRoot();
    assertEquals("message", root.getName());

    Node person = child(root, "person");
    assertEquals("7", child(person, "id").getValue());
    assertEquals("Fred", child(person, "firstname").getValue());
    assertEquals("true", child(person, "active").getValue());
    assertEquals("42", child(person, "age").getValue());
    assertEquals("7", hierarchy.select(HierarchyPath.fromSlashPath("/message/person/id")).getFirst().getValue());
  }

  @Test
  void mapsComplexYamlFileToHierarchy() throws Exception {
    Path input = tempDir.resolve("complex.yaml");
    Files.writeString(input, """
        message:
          source: client
          person:
            - id: "7"
              name:
                firstname: Fred
                surname: Flintstone
              nickname:
                - Ace
                - ${nickname}
              active: true
              score: 12.5
              middleName:
            - id: "8"
              name:
                firstname: Wilma
                surname: Flintstone
              nickname:
                - Wills
              active: false
              tag:
                - code: friend
                - code: vip
        """, StandardCharsets.UTF_8);

    Hierarchy hierarchy = new YamlInputReader().load(input.toString(), Map.of("nickname", "Freddy"));

    Node root = hierarchy.getRoot();
    assertEquals("message", root.getName());
    assertEquals("client", child(root, "source").getValue());

    List<Node> people = children(root, "person");
    assertEquals(2, people.size());

    Node fred = people.get(0);
    assertTrue(fred.isArrayItem());
    assertEquals("7", child(fred, "id").getValue());
    assertEquals("Fred", child(child(fred, "name"), "firstname").getValue());
    assertEquals(List.of("Ace", "Freddy"), children(fred, "nickname").stream()
        .map(Node::getValue)
        .toList());
    assertTrue(child(fred, "nickname").isArrayItem());
    assertEquals("true", child(fred, "active").getValue());
    assertEquals("12.5", child(fred, "score").getValue());
    assertTrue(child(fred, "middleName").isNull());

    Node wilma = people.get(1);
    assertTrue(wilma.isArrayItem());
    assertEquals("8", child(wilma, "id").getValue());
    assertEquals("Wilma", child(child(wilma, "name"), "firstname").getValue());
    assertEquals("false", child(wilma, "active").getValue());
    assertTrue(child(wilma, "tag").isArrayItem());
    assertEquals(List.of("friend", "vip"), children(wilma, "tag").stream()
        .map(tag -> child(tag, "code").getValue())
        .toList());
  }

  @Test
  void emptyFilenameReturnsEmptyHierarchy() {
    Hierarchy hierarchy = new YamlInputReader().load("", Map.of());

    assertTrue(hierarchy.isEmpty());
  }

  @Test
  void emptyFileReturnsEmptyHierarchy() throws Exception {
    Path input = tempDir.resolve("empty.yaml");
    Files.writeString(input, "", StandardCharsets.UTF_8);

    Hierarchy hierarchy = new YamlInputReader().load(input.toString(), Map.of());

    assertTrue(hierarchy.isEmpty());
  }

  @Test
  void malformedYamlThrowsClearException() throws Exception {
    Path input = tempDir.resolve("bad.yaml");
    Files.writeString(input, "message: [unterminated", StandardCharsets.UTF_8);

    IllegalStateException thrown = assertThrows(
        IllegalStateException.class,
        () -> new YamlInputReader().load(input.toString(), Map.of()));

    assertEquals("Malformed YAML input file: " + input, thrown.getMessage());
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
