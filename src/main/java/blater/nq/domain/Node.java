package blater.nq.domain;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/*
 * Responsibility: Represents one named result hierarchy node.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public final class Node {
  private String name = null;
  private String value = null;
  private ScalarKind scalarKind = ScalarKind.STRING;
  private boolean nullValue = false;
  // Renders as an XML attribute on its parent rather than a child element.
  private boolean attribute = false;
  // Marks a node as an item in a repeated collection, including keyed output nodes.
  private boolean arrayItem = false;
  // Marks an output container whose children are anonymous collection items.
  private boolean collection = false;
  @Setter(AccessLevel.NONE)
  @ToString.Exclude
  @EqualsAndHashCode.Exclude
  private Node parent;
  @Setter(AccessLevel.NONE)
  private final List<Node> children = new ArrayList<>();

  public Node(String name) {
     this.name = name;
     value = null;
     nullValue = false;
  }

  public boolean isNull() {
    return nullValue;
  }

  public static boolean isNull(Node n) {
    return n== null || n.value == null || n.nullValue;
  }

  public static boolean hasValue(Node n) {
    return n!= null && n.hasValue();
  }

  public boolean hasValue() {
    return value != null || nullValue;
  }

  public Node parent() {
    return parent;
  }

  public void addNode(Node child) {
    Objects.requireNonNull(child, "child");
    child.parent = this;
    children.add(child);
  }

  public void replaceChild(int index, Node child) {
    Objects.requireNonNull(child, "child");
    Node previous = children.set(index, child);
    if (previous != null && previous.parent == this) {
      previous.parent = null;
    }
    child.parent = this;
  }

}
