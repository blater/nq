package blater.nql.outputwriter.json;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.domain.ScalarKind;
import blater.nql.outputwriter.JsonOutputWriter;
import blater.nql.outputwriter.OutputType;
import blater.nql.outputwriter.OutputWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JsonOutputWriterTest {
  @Test
  void mapsSimpleHierarchyAsJsonObject() {
    Node root = new Node("people");
    Node person = new Node("person");
    person.addNode(valueNode("firstname", "Alice"));
    root.addNode(person);

    assertEquals(
        "{\"people\":{\"person\":{\"firstname\":\"Alice\"}}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void repeatedSiblingNodesRenderAsArrays() {
    Node root = new Node("people");
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    assertEquals(
        "{\"people\":{\"person\":[{\"id\":\"1\",\"firstname\":\"Alice\"},{\"id\":\"2\",\"firstname\":\"Bob\"}]}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void oneCollectionItemStillRendersAsArray() {
    Node root = new Node("people");
    Node person = person("1", "Alice");
    person.setArrayItem(true);
    root.addNode(person);

    assertEquals(
        "{\"people\":{\"person\":[{\"id\":\"1\",\"firstname\":\"Alice\"}]}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void nullValuesRenderAsJsonNull() {
    Node root = new Node("person");
    Node middleName = new Node("middleName");
    middleName.setNullValue(true);
    root.addNode(middleName);

    assertEquals(
        "{\"person\":{\"middleName\":null}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void attributesRenderAsOrdinaryJsonProperties() {
    Node root = new Node("person");
    Node id = valueNode("id", "7");
    id.setAttribute(true);
    root.addNode(id);

    assertEquals(
        "{\"person\":{\"id\":\"7\"}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void attributeAndElementWithSameNamePreserveBothValues() {
    Node root = new Node("person");
    Node attributeId = valueNode("id", "7");
    attributeId.setAttribute(true);
    root.addNode(attributeId);
    root.addNode(valueNode("id", "internal"));

    assertEquals(
        "{\"person\":{\"id\":[\"7\",\"internal\"]}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void emptyHierarchyMapsAsEmptyJsonObject() {
    assertEquals("{}", JsonOutputWriter.map(new Hierarchy()));
  }

  @Test
  void escapesJsonStrings() {
    Node root = new Node("message");
    root.addNode(valueNode("text", "quote \" slash \\ newline\n"));

    assertEquals(
        "{\"message\":{\"text\":\"quote \\\" slash \\\\ newline\\n\"}}",
        JsonOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void rendersTypedNumbersAndBooleansAsJsonScalars() {
    Node root = new Node("record");
    Node count = valueNode("count", "7.5");
    count.setScalarKind(ScalarKind.NUMBER);
    root.addNode(count);
    Node active = valueNode("active", "false");
    active.setScalarKind(ScalarKind.BOOLEAN);
    root.addNode(active);

    assertEquals(
        "{\"record\":{\"count\":7.5,\"active\":false}}",
        JsonOutputWriter.map(new Hierarchy(root)));
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
