package blater.nql.outputwriter.csv;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.outputwriter.CsvOutputWriter;
import blater.nql.outputwriter.OutputType;
import blater.nql.outputwriter.OutputWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CsvOutputWriterTest {
  @Test
  void repeatedRootChildrenRenderAsCsvRecords() {
    Node root = new Node("people");
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    assertEquals("""
        id,firstname
        1,Alice
        2,Bob
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void oneRootCollectionItemStillRendersAsCsvRecord() {
    Node root = new Node("people");
    Node person = person("1", "Alice");
    person.setArrayItem(true);
    root.addNode(person);

    assertEquals("""
        id,firstname
        1,Alice
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void anonymousRootRendersItsSingleChildAsARecord() {
    Node root = new Node("");
    root.addNode(person("1", "Alice"));

    assertEquals("""
        id,firstname
        1,Alice
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void nestedScalarHierarchyRendersDottedColumns() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "1"));
    Node address = new Node("address");
    address.addNode(valueNode("city", "Bedrock"));
    address.addNode(valueNode("postcode", "BR1"));
    root.addNode(address);

    assertEquals("""
        id,address.city,address.postcode
        1,Bedrock,BR1
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void repeatedScalarChildrenExpandIntoRows() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "1"));
    root.addNode(valueNode("nickname", "Ace"));
    root.addNode(valueNode("nickname", "Freddy"));

    assertEquals("""
        id,nickname
        1,Ace
        1,Freddy
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void repeatedNestedObjectsExpandIntoRows() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "1"));
    root.addNode(phone("111"));
    root.addNode(phone("222"));

    assertEquals("""
        id,phone.number
        1,111
        1,222
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void attributesRenderAsOrdinaryCsvColumns() {
    Node root = new Node("person");
    Node id = valueNode("id", "7");
    id.setAttribute(true);
    root.addNode(id);

    assertEquals("""
        id
        7
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void nullValuesRenderAsEmptyCells() {
    Node root = new Node("person");
    root.addNode(valueNode("id", "1"));
    Node middleName = new Node("middleName");
    middleName.setNullValue(true);
    root.addNode(middleName);

    assertEquals("""
        id,middleName
        1,
        """, CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void csvEscapingHandlesCommasQuotesAndNewlines() {
    Node root = new Node("message");
    root.addNode(valueNode("text", "hello, \"friend\"\nagain"));

    assertEquals("text\n\"hello, \"\"friend\"\"\nagain\"\n", CsvOutputWriter.map(new Hierarchy(root)));
  }

  @Test
  void emptyHierarchyMapsAsEmptyString() {
    assertEquals("", CsvOutputWriter.map(new Hierarchy()));
  }

  private Node person(String id, String firstname) {
    Node person = new Node("person");
    person.addNode(valueNode("id", id));
    person.addNode(valueNode("firstname", firstname));
    return person;
  }

  private Node phone(String number) {
    Node phone = new Node("phone");
    phone.addNode(valueNode("number", number));
    return phone;
  }

  private Node valueNode(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }
}
