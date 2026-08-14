package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.domain.ScalarKind;
import blater.nq.util.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Maps parser-neutral maps, lists, and scalars into a hierarchy.
 */
final class StructuredDataInputMapper {
  private static final String ARRAY_ITEM = "item";

  private StructuredDataInputMapper() {
  }

  static Hierarchy toHierarchy(
      Object input,
      String syntheticRoot,
      Map<String, String> parameters) {
    if (input == null) {
      return new Hierarchy();
    }

    if (input instanceof Map<?, ?> object && object.size() == 1) {
      Map.Entry<?, ?> root = object.entrySet().iterator().next();
      return new Hierarchy(toNode(root.getKey().toString(), root.getValue(), parameters));
    }

    if (input instanceof Map<?, ?>) {
      return new Hierarchy(
          toNode(syntheticRoot, input, parameters),
          Hierarchy.RootKind.SYNTHETIC_OBJECT);
    }

    if (input instanceof List<?>) {
      return new Hierarchy(
          toNode(syntheticRoot, input, parameters),
          Hierarchy.RootKind.SYNTHETIC_ARRAY);
    }

    return new Hierarchy(toNode(syntheticRoot, input, parameters));
  }

  private static Node toNode(String name, Object value, Map<String, String> parameters) {
    Node node = new Node(name);

    if (value == null) {
      node.setNullValue(true);
      return node;
    }

    if (value instanceof Map<?, ?> object) {
      for (Map.Entry<?, ?> entry : object.entrySet()) {
        for (Node child : toChildNodes(entry.getKey().toString(), entry.getValue(), parameters)) {
          node.addNode(child);
        }
      }
      return node;
    }

    if (value instanceof List<?> array) {
      for (Object item : array) {
        Node child = toNode(ARRAY_ITEM, item, parameters);
        child.setArrayItem(true);
        node.addNode(child);
      }
      return node;
    }

    if (value instanceof String stringValue) {
      node.setValue(Template.expand(stringValue, parameters));
    } else {
      node.setValue(value.toString());
      if (value instanceof Number) {
        node.setScalarKind(ScalarKind.NUMBER);
      } else if (value instanceof Boolean) {
        node.setScalarKind(ScalarKind.BOOLEAN);
      }
    }
    return node;
  }

  private static List<Node> toChildNodes(
      String name,
      Object value,
      Map<String, String> parameters) {
    if (!(value instanceof List<?> array)) {
      return List.of(toNode(name, value, parameters));
    }
    List<Node> nodes = new ArrayList<>(array.size());
    for (Object item : array) {
      Node node = toNode(name, item, parameters);
      node.setArrayItem(true);
      nodes.add(node);
    }
    return nodes;
  }
}
