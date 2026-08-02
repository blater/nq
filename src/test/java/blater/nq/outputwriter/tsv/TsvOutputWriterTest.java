package blater.nq.outputwriter.tsv;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.outputwriter.TsvOutputWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TsvOutputWriterTest {
  @Test
  void flattensAndExpandsRecordsUsingTabs() {
    Node root = new Node("people");
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    assertEquals(
        "id\tname\n1\tAlice\n2\tBob\n",
        TsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void quotesTabsQuotesAndNewlines() {
    Node root = new Node("message");
    root.addNode(valueNode("text", "hello\t\"friend\"\nagain"));

    assertEquals(
        "text\n\"hello\t\"\"friend\"\"\nagain\"\n",
        TsvOutputWriter.map(new Hierarchy(root)));
  }

  private Node person(String id, String name) {
    Node person = new Node("person");
    person.addNode(valueNode("id", id));
    person.addNode(valueNode("name", name));
    return person;
  }

  private Node valueNode(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }
}
