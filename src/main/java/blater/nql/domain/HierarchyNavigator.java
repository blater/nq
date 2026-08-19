package blater.nql.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Provides read and targeted update navigation over an existing node hierarchy. */
final class HierarchyNavigator {
  private HierarchyNavigator() {
  }

  static List<Node> select(Node root, HierarchyPath path) {
    if (!hasMatchingRoot(root, path)) {
      return List.of();
    }
    if (path.isRoot()) {
      return path.isAttribute() ? List.of() : List.of(root);
    }

    List<Node> matches = List.of(root);
    for (int index = 1; index < path.getPathParts().size(); index++) {
      String segment = path.getPathParts().get(index);
      boolean terminalAttribute = index == path.getPathParts().size() - 1 && path.isAttribute();
      matches = matchingChildren(matches, segment, terminalAttribute);
      if (matches.isEmpty()) {
        return List.of();
      }
    }
    return matches;
  }

  static List<Node> ensureFinalTargets(Node root, HierarchyPath path, String defaultValue) {
    if (root == null || path == null || path.isRoot()) {
      return List.of();
    }
    List<Node> parents = select(root, path.parent());
    if (parents.isEmpty()) {
      return List.of();
    }

    List<Node> targets = new ArrayList<>();
    for (Node parent : parents) {
      Node target = firstChild(parent, path.getTerminalNodeName(), path.isAttribute());
      if (target == null) {
        target = valueNode(path, defaultValue);
        parent.addNode(target);
      } else if (shouldApplyDefault(target, defaultValue)) {
        target.setValue(defaultValue);
      }
      targets.add(target);
    }
    return targets;
  }

  private static boolean hasMatchingRoot(Node root, HierarchyPath path) {
    return root != null
        && path != null
        && !path.getPathParts().isEmpty()
        && Objects.equals(root.getName(), path.getRootName());
  }

  private static List<Node> matchingChildren(
      List<Node> parents, String name, boolean attribute) {
    List<Node> matches = new ArrayList<>();
    for (Node parent : parents) {
      parent.getChildren().stream()
          .filter(child -> Objects.equals(child.getName(), name))
          .filter(child -> child.isAttribute() == attribute)
          .forEach(matches::add);
    }
    return matches;
  }

  private static Node firstChild(Node parent, String name, boolean attribute) {
    return parent.getChildren().stream()
        .filter(child -> Objects.equals(child.getName(), name))
        .filter(child -> child.isAttribute() == attribute)
        .findFirst()
        .orElse(null);
  }

  private static Node valueNode(HierarchyPath path, String defaultValue) {
    Node node = new Node(path.getTerminalNodeName());
    node.setValue(defaultValue == null ? "" : defaultValue);
    node.setNullValue(false);
    node.setAttribute(path.isAttribute());
    return node;
  }

  private static boolean shouldApplyDefault(Node target, String defaultValue) {
    return defaultValue != null
        && (!target.hasValue() || target.getValue() != null && target.getValue().isEmpty());
  }
}
