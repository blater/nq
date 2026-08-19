package blater.nql.outputwriter;

import blater.nql.domain.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Maps hierarchy nodes into parser-neutral maps, lists, and scalars.
 */
final class StructuredDataOutputMapper {
  private StructuredDataOutputMapper() {
  }

  static Object nodeValue(Node node) {
    if (node.isCollection()) {
      return node.getChildren().stream()
          .map(StructuredDataOutputMapper::rootItemValue)
          .toList();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.hasValue()) {
      return node.getValue();
    }

    Map<String, Object> object = new LinkedHashMap<>();
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      List<Node> children = entry.getValue();
      if (children.size() == 1 && !children.getFirst().isArrayItem()) {
        object.put(entry.getKey(), nodeValue(children.getFirst()));
      } else {
        object.put(entry.getKey(), children.stream()
            .map(StructuredDataOutputMapper::nodeValue)
            .toList());
      }
    }
    return object;
  }

  static Object rootItemValue(Node node) {
    return node.getName() == null || node.getName().isEmpty()
        ? nodeValue(node)
        : namedNodeValue(node);
  }

  private static Object namedNodeValue(Node node) {
    Map<String, Object> named = new LinkedHashMap<>();
    named.put(node.getName(), nodeValue(node));
    return named;
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }
}
