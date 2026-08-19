package blater.nql.report;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps an NQL hierarchy into values suitable for an operational report's details object. */
public final class HierarchyReportMapper {
  private HierarchyReportMapper() {
  }

  public static Map<String, ?> details(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null || root.getName().isEmpty()) {
      return Map.of();
    }
    return Map.of(root.getName(), value(root));
  }

  private static Object value(Node node) {
    if (node.isCollection()) {
      return node.getChildren().stream().map(HierarchyReportMapper::value).toList();
    }
    if (node.isNull()) {
      return null;
    }
    if (node.hasValue()) {
      if ("nullable".equals(node.getName())
          && ("true".equalsIgnoreCase(node.getValue())
              || "false".equalsIgnoreCase(node.getValue()))) {
        return Boolean.valueOf(node.getValue());
      }
      return node.getValue();
    }

    Map<String, Object> object = new LinkedHashMap<>();
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      List<Node> children = entry.getValue();
      if (children.size() == 1 && !children.getFirst().isArrayItem()
          && !catalogCollection(node.getName(), entry.getKey())) {
        object.put(entry.getKey(), value(children.getFirst()));
      } else {
        object.put(entry.getKey(), children.stream().map(HierarchyReportMapper::value).toList());
      }
    }
    return object;
  }

  private static boolean catalogCollection(String parent, String child) {
    return "catalog".equals(parent) && "table".equals(child)
        || "columns".equals(parent) && "column".equals(child);
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }
}
