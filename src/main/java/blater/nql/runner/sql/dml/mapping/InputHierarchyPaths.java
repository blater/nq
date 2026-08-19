package blater.nql.runner.sql.dml.mapping;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.HierarchyPath;
import blater.nql.domain.Node;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Adapts public DML paths to hierarchy storage paths and creates write-back targets. */
final class InputHierarchyPaths {
  private static final Set<String> DELIMITED_ROOTS = Set.of("csv", "tsv");

  private InputHierarchyPaths() {
  }

  static List<Node> selectNodes(Hierarchy input, String xpath) {
    HierarchyPath path = parsePath(xpath);
    if (path == null) {
      return null;
    }
    for (HierarchyPath candidate : sourcePathCandidates(input, path)) {
      List<Node> nodes = input.select(candidate);
      if (!nodes.isEmpty()) {
        return nodes;
      }
    }
    return List.of();
  }

  static boolean ensureDbAssignedTarget(Hierarchy input, InputToColumnMap mapping) {
    if (!needsDbAssignment(mapping) || !mapping.xpathMapping().startsWith("/")) {
      return true;
    }
    return ensureTargetPath(input, mapping.xpathMapping(), mapping.defaultValue());
  }

  static boolean ensureReturnTargets(Hierarchy input, List<ReturnMapping> returnMappings) {
    for (ReturnMapping mapping : returnMappings) {
      if (!ensureTargetPath(input, mapping.getXpath(), null)) {
        return false;
      }
    }
    return true;
  }

  static boolean needsDbAssignment(InputToColumnMap mapping) {
    return (mapping.columnDefinition().isUid() && mapping.columnDefinition().key())
        || (mapping.columnDefinition().isDbAssigned() && !mapping.columnDefinition().key());
  }

  private static boolean ensureTargetPath(Hierarchy input, String xpath, String ifNull) {
    if (!xpath.startsWith("/")) {
      return true;
    }
    HierarchyPath path = parsePath(xpath);
    if (path == null) {
      return false;
    }
    for (HierarchyPath candidate : sourcePathCandidates(input, path)) {
      HierarchyPath parent = candidate.parent();
      if (parent != null && !input.select(parent).isEmpty()) {
        input.ensureFinalTargets(candidate, ifNull);
        break;
      }
    }
    return true;
  }

  private static List<HierarchyPath> sourcePathCandidates(
      Hierarchy input,
      HierarchyPath requested) {

    Node root = input.getRoot();
    if (root == null) {
      return List.of(requested);
    }
    List<HierarchyPath> candidates = new ArrayList<>();
    List<String> parts = requested.getPathParts();
    if (root.getName().equals(requested.getRootName())
        && hasAnonymousArrayItems(root)
        && (parts.size() == 1 || !"item".equals(parts.get(1)))) {
      List<String> expanded = new ArrayList<>(parts);
      expanded.add(1, "item");
      candidates.add(new HierarchyPath(expanded, requested.isAttribute()));
    }
    candidates.add(requested);
    if (input.getRootKind() == Hierarchy.RootKind.SYNTHETIC_OBJECT
        && !root.getName().equals(requested.getRootName())
        && hasDirectElementChild(root, requested.getRootName())) {
      List<String> expanded = new ArrayList<>(parts.size() + 1);
      expanded.add(root.getName());
      expanded.addAll(parts);
      candidates.add(new HierarchyPath(expanded, requested.isAttribute()));
    }
    return candidates;
  }

  private static boolean hasAnonymousArrayItems(Node root) {
    return root.getChildren().stream()
        .anyMatch(child -> "item".equals(child.getName())
            && (child.isArrayItem() || DELIMITED_ROOTS.contains(root.getName())));
  }

  private static boolean hasDirectElementChild(Node root, String name) {
    return root.getChildren().stream()
        .anyMatch(child -> !child.isAttribute() && name.equals(child.getName()));
  }

  private static HierarchyPath parsePath(String sourcePath) {
    try {
      return HierarchyPath.fromSlashPath(sourcePath);
    } catch (IllegalArgumentException ex) {
      Log.error("Unsupported source path [{}]: {} Use a simple hierarchy path such as "
          + "{message.person.id}; NQL paths are not XPath, JSONPath, or YAMLPath.", sourcePath, ex.getMessage());
      return null;
    }
  }
}
