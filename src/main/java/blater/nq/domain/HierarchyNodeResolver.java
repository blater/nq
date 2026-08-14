package blater.nq.domain;

import blater.nq.runner.sql.domain.QueryResultRow;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/** Resolves hierarchy paths and maintains keyed child identity across rows. */
final class HierarchyNodeResolver {
  private final HierarchyKeyIndex keyIndex = new HierarchyKeyIndex();

  HierarchyKeyIndex keyIndex() {
    return keyIndex;
  }

  Node resolveParent(
      Node root,
      Hierarchy.RootKind rootKind,
      MappingPlan plan,
      HierarchyPath path,
      QueryResultRow row,
      RowContext rowContext) {
    if (path == null) {
      return root;
    }

    Node current = root;
    HierarchyPath currentPath = initialPath(rootKind, path);
    int start = rootKind == Hierarchy.RootKind.NAMED ? 1 : 0;
    if (rootKind == Hierarchy.RootKind.NAMED && path.isRoot()) {
      return root;
    }
    for (int index = start; index < path.getPathParts().size(); index++) {
      currentPath = nextPath(currentPath, path.getPathParts().get(index));
      KeyedPath repeated = repeatedPath(plan, currentPath);
      if (isSyntheticArrayOwner(rootKind, plan, currentPath, repeated)) {
        current = syntheticArrayItem(root, plan, currentPath, row, rowContext);
      } else if (repeated != null) {
        current = repeatedChild(current, currentPath, repeated, row, rowContext);
      } else if (isInferredOwner(plan, currentPath)) {
        current = keyIndex.singletonChild(current, currentPath);
      } else if (isObjectPath(plan, currentPath)) {
        current = rowContext.objectChild(current, currentPath);
      } else {
        current = keyIndex.singletonChild(current, currentPath);
      }
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private Node syntheticArrayItem(
      Node root,
      MappingPlan plan,
      HierarchyPath path,
      QueryResultRow row,
      RowContext rowContext) {
    KeyedPath owner = keyedPath(plan, path);
    HierarchyKeyIndex.KeyState state = keyIndex.keyState(owner, row);
    if (state == HierarchyKeyIndex.KeyState.ABSENT) {
      return null;
    }
    Node item = state == HierarchyKeyIndex.KeyState.PARTIAL
        ? rowContext.anonymousChild(root, owner.path())
        : keyIndex.keyedAnonymousChild(root, owner.path(), keyIndex.keyTuple(owner, row));
    return keyIndex.singletonChild(item, path);
  }

  private boolean isSyntheticArrayOwner(
      Hierarchy.RootKind rootKind,
      MappingPlan plan,
      HierarchyPath path,
      KeyedPath repeated) {
    if (repeated != null || rootKind != Hierarchy.RootKind.SYNTHETIC_ARRAY) {
      return false;
    }
    KeyedPath owner = keyedPath(plan, path);
    return owner != null
        && owner.placement() instanceof RepetitionPlacement.AnonymousItem anonymous
        && anonymous.containerPath().isEmpty();
  }

  private Node repeatedChild(
      Node current,
      HierarchyPath path,
      KeyedPath repeated,
      QueryResultRow row,
      RowContext rowContext) {
    HierarchyKeyIndex.KeyState state = keyIndex.keyState(repeated, row);
    if (state == HierarchyKeyIndex.KeyState.ABSENT) {
      return null;
    }
    if (state == HierarchyKeyIndex.KeyState.PARTIAL && !repeated.inferred()) {
      throw new IllegalStateException("Partially null structure key: " + path);
    }
    if (repeated.placement() instanceof RepetitionPlacement.AnonymousItem) {
      Node collection = keyIndex.singletonChild(current, path);
      collection.setCollection(true);
      return state == HierarchyKeyIndex.KeyState.PARTIAL
          ? rowContext.anonymousChild(collection, repeated.path())
          : keyIndex.keyedAnonymousChild(
              collection, repeated.path(), keyIndex.keyTuple(repeated, row));
    }
    return state == HierarchyKeyIndex.KeyState.PARTIAL
        ? rowContext.objectChild(current, path)
        : keyIndex.keyedChild(current, path, keyIndex.keyTuple(repeated, row));
  }

  KeyedPath keyedPath(MappingPlan plan, HierarchyPath path) {
    return plan.getKeyedPaths().stream()
        .filter(key -> key.path().equals(path))
        .findFirst()
        .orElse(null);
  }

  KeyedPath repeatedPath(MappingPlan plan, HierarchyPath path) {
    return plan.getKeyedPaths().stream()
        .filter(key -> repeatsAt(key, path))
        .findFirst()
        .orElse(null);
  }

  boolean flatRows(MappingPlan plan) {
    return !plan.getFields().isEmpty()
        && plan.getFields().stream().allMatch(
            field -> field.getPath().getPathParts().size() == 1);
  }

  void initializeInferredCollections(
      Node root, Hierarchy.RootKind rootKind, MappingPlan plan) {
    for (KeyedPath key : plan.getKeyedPaths()) {
      if (!(key.placement() instanceof RepetitionPlacement.AnonymousItem anonymous)) {
        continue;
      }
      Optional<HierarchyPath> collectionPath = anonymous.containerPath();
      if (collectionPath.isEmpty()) {
        root.setCollection(true);
      } else {
        collectionNode(root, rootKind, collectionPath.get()).setCollection(true);
      }
    }
  }

  private static HierarchyPath initialPath(Hierarchy.RootKind rootKind, HierarchyPath path) {
    return rootKind == Hierarchy.RootKind.NAMED
        ? HierarchyPath.fromDottedPath(path.getRootName())
        : null;
  }

  private static HierarchyPath nextPath(HierarchyPath current, String name) {
    return current == null ? HierarchyPath.fromDottedPath(name) : current.child(name);
  }

  private static boolean repeatsAt(KeyedPath key, HierarchyPath path) {
    if (key.placement() instanceof RepetitionPlacement.NamedItem) {
      return path.equals(key.identityPath());
    }
    return key.placement() instanceof RepetitionPlacement.AnonymousItem anonymous
        && anonymous.containerPath().filter(path::equals).isPresent();
  }

  private boolean isInferredOwner(MappingPlan plan, HierarchyPath path) {
    KeyedPath key = keyedPath(plan, path);
    return key != null && key.placement() instanceof RepetitionPlacement.AnonymousItem;
  }

  private boolean isObjectPath(MappingPlan plan, HierarchyPath path) {
    return !path.isRoot() && (keyedPath(plan, path) != null
        || plan.getFields().stream().anyMatch(field -> path.equals(field.getPath().parent())));
  }

  private Node collectionNode(Node root, Hierarchy.RootKind rootKind, HierarchyPath path) {
    Node current = root;
    HierarchyPath currentPath = initialPath(rootKind, path);
    int start = rootKind == Hierarchy.RootKind.NAMED ? 1 : 0;
    if (rootKind == Hierarchy.RootKind.NAMED && path.isRoot()) {
      return root;
    }
    for (int index = start; index < path.getPathParts().size(); index++) {
      currentPath = nextPath(currentPath, path.getPathParts().get(index));
      current = keyIndex.singletonChild(current, currentPath);
    }
    return current;
  }

  static final class RowContext {
    private final IdentityHashMap<Node, Map<HierarchyPath, Node>> objects = new IdentityHashMap<>();

    Node objectChild(Node parent, HierarchyPath path) {
      return child(parent, path, path.getTerminalNodeName());
    }

    Node anonymousChild(Node parent, HierarchyPath path) {
      return child(parent, path, "");
    }

    private Node child(Node parent, HierarchyPath path, String name) {
      return objects.computeIfAbsent(parent, ignored -> new HashMap<>())
          .computeIfAbsent(path, ignored -> {
            Node child = new Node(name);
            parent.addNode(child);
            return child;
          });
    }
  }

}
