package blater.nq.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyNavigationTest {
  @Test
  void selectsElementAndAttributePaths() {
    Node root = new Node("message");
    Node person = new Node("person");
    Node id = new Node("id");
    id.setAttribute(true);
    id.setValue("7");
    Node firstname = new Node("firstname");
    firstname.setValue("Fred");
    root.addNode(person);
    person.addNode(id);
    person.addNode(firstname);
    Hierarchy hierarchy = new Hierarchy(root);

    assertSame(firstname, hierarchy.select(HierarchyPath.fromSlashPath("/message/person/firstname")).getFirst());
    assertSame(id, hierarchy.select(HierarchyPath.fromSlashPath("/message/person/@id")).getFirst());
    assertTrue(hierarchy.select(HierarchyPath.fromSlashPath("/message/person/id")).isEmpty());
  }

  @Test
  void ensureFinalTargetsCreatesTerminalNodeBelowExistingParentsOnly() {
    Node root = new Node("message");
    Node first = new Node("person");
    Node second = new Node("person");
    root.addNode(first);
    root.addNode(second);
    Hierarchy hierarchy = new Hierarchy(root);

    List<Node> targets = hierarchy.ensureFinalTargets(
        HierarchyPath.fromSlashPath("/message/person/id"),
        null);

    assertEquals(2, targets.size());
    assertEquals("", targets.get(0).getValue());
    assertEquals("", targets.get(1).getValue());
    assertSame(first, targets.get(0).parent());
    assertSame(second, targets.get(1).parent());
  }

  @Test
  void ensureFinalTargetsCreatesTerminalAttribute() {
    Node root = new Node("message");
    Node person = new Node("person");
    root.addNode(person);
    Hierarchy hierarchy = new Hierarchy(root);

    List<Node> targets = hierarchy.ensureFinalTargets(
        HierarchyPath.fromSlashPath("/message/person/@id"),
        "7");

    assertEquals(1, targets.size());
    assertTrue(targets.getFirst().isAttribute());
    assertEquals("id", targets.getFirst().getName());
    assertEquals("7", targets.getFirst().getValue());
    assertSame(person, targets.getFirst().parent());
  }
}
