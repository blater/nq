package blater.nql.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NodeTest {
  @Test
  void addNodeAssignsParent() {
    Node parent = new Node("parent");
    Node child = new Node("child");

    parent.addNode(child);

    assertNull(parent.parent());
    assertSame(parent, child.parent());
  }

  @Test
  void replaceChildAssignsNewParentAndClearsPreviousParent() {
    Node parent = new Node("parent");
    Node previous = new Node("child");
    Node replacement = new Node("child");
    parent.addNode(previous);

    parent.replaceChild(0, replacement);

    assertNull(previous.parent());
    assertSame(parent, replacement.parent());
  }

  @Test
  void parentHasNoPublicSetter() {
    boolean hasPublicParentSetter = Arrays.stream(Node.class.getMethods())
        .anyMatch(method -> method.getName().equals("setParent"));

    assertFalse(hasPublicParentSetter);
  }
}
