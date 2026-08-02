package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TsvInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsTsvInputReader() {
    assertInstanceOf(TsvInputReader.class, InputReader.of(InputType.TSV));
  }

  @Test
  void supportsTheSameNestedHeadersTemplatesMissingCellsAndQuotingAsCsv() throws Exception {
    Path input = tempDir.resolve("people.tsv");
    Files.writeString(
        input,
        "person.firstname\tperson.note\tperson.surname\n"
            + "${firstname}\t\"hello\t\"\"friend\"\"\nagain\"\n",
        StandardCharsets.UTF_8);

    Hierarchy hierarchy = new TsvInputReader().load(
        input.toString(),
        Map.of("firstname", "Fred"));

    assertEquals("tsv", hierarchy.getRoot().getName());
    Node person = child(child(hierarchy.getRoot(), "item"), "person");
    assertEquals("Fred", child(person, "firstname").getValue());
    assertEquals("hello\t\"friend\"\nagain", child(person, "note").getValue());
    assertEquals("", child(person, "surname").getValue());
  }

  private Node child(Node parent, String name) {
    return parent.getChildren().stream()
        .filter(candidate -> candidate.getName().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
