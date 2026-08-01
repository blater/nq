package blater.nq.outputwriter.yaml;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.outputwriter.OutputType;
import blater.nq.outputwriter.OutputWriter;
import blater.nq.outputwriter.YamlOutputWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class YamlOutputWriterTest {
  @Test
  void mapsSimpleHierarchyAsYamlMapping() {
    Node root = new Node("people");
    Node person = new Node("person");
    person.addNode(valueNode("firstname", "Alice"));
    root.addNode(person);

    assertEquals("""
        people:
          person:
            firstname: "Alice"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void repeatedSiblingNodesRenderAsSequences() {
    Node root = new Node("people");
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    assertEquals("""
        people:
          person:
            -
              id: "1"
              firstname: "Alice"
            -
              id: "2"
              firstname: "Bob"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void oneCollectionItemStillRendersAsSequence() {
    Node root = new Node("people");
    Node person = person("1", "Alice");
    person.setArrayItem(true);
    root.addNode(person);

    assertEquals("""
        people:
          person:
            -
              id: "1"
              firstname: "Alice"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void anonymousRootRetainsEachObjectsLogicalName() {
    Node root = new Node("");
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    assertEquals("""
        -
          person:
            id: "1"
            firstname: "Alice"
        -
          person:
            id: "2"
            firstname: "Bob"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void namedInferredCollectionRemainsASequenceWithOneItem() {
    Node root = new Node("");
    Node collection = new Node("res");
    collection.setCollection(true);
    Node item = new Node("");
    item.addNode(person("1", "Alice"));
    collection.addNode(item);
    root.addNode(collection);

    assertEquals("""
        res:
          -
            person:
              id: "1"
              firstname: "Alice"
        """, YamlOutputWriter.map(new Hierarchy(root, Hierarchy.RootKind.SYNTHETIC_OBJECT)));
  }

  @Test
  void nullValuesRenderAsYamlNull() {
    Node root = new Node("person");
    Node middleName = new Node("middleName");
    middleName.setNullValue(true);
    root.addNode(middleName);

    assertEquals("""
        person:
          middleName: null
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void attributesRenderAsOrdinaryYamlProperties() {
    Node root = new Node("person");
    Node id = valueNode("id", "7");
    id.setAttribute(true);
    root.addNode(id);

    assertEquals("""
        person:
          id: "7"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void attributeAndElementWithSameNamePreserveBothValues() {
    Node root = new Node("person");
    Node attributeId = valueNode("id", "7");
    attributeId.setAttribute(true);
    root.addNode(attributeId);
    root.addNode(valueNode("id", "internal"));

    assertEquals("""
        person:
          id:
            - "7"
            - "internal"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void emptyHierarchyMapsAsEmptyYamlObject() {
    assertEquals("{}", YamlOutputWriter.map(new Hierarchy()));
  }

  @Test
  void escapesYamlStrings() {
    Node root = new Node("message");
    root.addNode(valueNode("text", "quote \" slash \\ newline\n"));

    assertEquals("""
        message:
          text: "quote \\" slash \\\\ newline\\n"
        """, YamlOutputWriter.map(new Hierarchy(root)));
  }

  private Node person(String id, String firstname) {
    Node person = new Node("person");
    person.addNode(valueNode("id", id));
    person.addNode(valueNode("firstname", firstname));
    return person;
  }

  private Node valueNode(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }
}
