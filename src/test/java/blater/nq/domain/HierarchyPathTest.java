package blater.nq.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyPathTest {
  @Test
  void slashPathParsesTerminalAttribute() {
    HierarchyPath path = HierarchyPath.fromSlashPath("/message/person/@id");

    assertEquals(List.of("message", "person", "id"), path.getPathParts());
    assertTrue(path.isAttribute());
  }

  @Test
  void terminalAttributeStateParticipatesInEquality() {
    HierarchyPath elementPath = HierarchyPath.fromDottedPath("message.person.id");
    HierarchyPath attributePath = HierarchyPath.fromDottedPath("message.person.@id");

    assertNotEquals(elementPath, attributePath);
    assertFalse(elementPath.isAttribute());
    assertTrue(attributePath.isAttribute());
  }

  @Test
  void parentAndChildReturnElementPaths() {
    HierarchyPath attributePath = HierarchyPath.fromSlashPath("/message/person/@id");

    assertFalse(attributePath.parent().isAttribute());
    assertFalse(attributePath.parent().child("name").isAttribute());
  }

  @Test
  void rejectsNonSimpleSlashPaths() {
    assertThrows(IllegalArgumentException.class, () -> HierarchyPath.fromSlashPath("/message/person[1]/id"));
    assertThrows(IllegalArgumentException.class, () -> HierarchyPath.fromSlashPath("//person/id"));
    assertThrows(IllegalArgumentException.class, () -> HierarchyPath.fromSlashPath("/message/person/*/id"));
    assertThrows(IllegalArgumentException.class, () -> HierarchyPath.fromSlashPath("/message/person/text()"));
  }
}
