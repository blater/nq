package blater.nq.outputwriter.toml;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.outputwriter.TomlOutputWriter;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TomlOutputWriterTest {
  @Test
  void writesNestedObjectsAndRepeatedObjectsAsTables() {
    Node root = new Node("people");
    root.addNode(valueNode("source", "database"));
    root.addNode(person("1", "Alice"));
    root.addNode(person("2", "Bob"));

    String output = TomlOutputWriter.map(new Hierarchy(root));

    assertEquals("""
        [people]
        source = "database"

        [[people.person]]
        id = "1"
        name = "Alice"

        [[people.person]]
        id = "2"
        name = "Bob"
        """, output);
    assertDoesNotThrow(() -> new TomlParser().parse(new StringReader(output)));
  }

  @Test
  void writesScalarArraysNestedTablesEscapedStringsAndQuotedKeys() {
    Node root = new Node("settings");
    Node firstTag = valueNode("tag", "one");
    firstTag.setArrayItem(true);
    root.addNode(firstTag);
    Node secondTag = valueNode("tag", "two");
    secondTag.setArrayItem(true);
    root.addNode(secondTag);
    root.addNode(valueNode("display name", "hello\t\"friend\"\nagain"));
    Node database = new Node("database");
    database.addNode(valueNode("host", "localhost"));
    root.addNode(database);

    String output = TomlOutputWriter.map(new Hierarchy(root));

    assertEquals(
        "[settings]\n"
            + "tag = [\"one\", \"two\"]\n"
            + "\"display name\" = \"hello\\t\\\"friend\\\"\\nagain\"\n"
            + "\n"
            + "[settings.database]\n"
            + "host = \"localhost\"\n",
        output);
    assertDoesNotThrow(() -> new TomlParser().parse(new StringReader(output)));
  }

  @Test
  void approximatesNullAsAnEmptyStringBecauseTomlHasNoNullLiteral() {
    Node root = new Node("person");
    Node value = new Node("middleName");
    value.setNullValue(true);
    root.addNode(value);

    assertEquals("""
        [person]
        middleName = ""
        """, TomlOutputWriter.map(new Hierarchy(root)));
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
