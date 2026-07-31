package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Neutral source facts kept beside the hierarchy used by DML mapping. */
public final class SourceStructure {
  public enum NodeKind { OBJECT, SCALAR, STRUCTURAL_CONTAINER }

  public record NodeMetadata(
      RelationPath valuePath,
      NodeKind kind,
      Optional<RelationPath> owningCollection,
      Optional<String> declaredName) { }

  public record CollectionMetadata(
      RelationPath path,
      Optional<String> declaredName,
      boolean anonymous,
      boolean empty) { }

  private final IdentityHashMap<Node, NodeMetadata> nodes;
  private final Map<RelationPath, CollectionMetadata> collections;

  private SourceStructure(
      IdentityHashMap<Node, NodeMetadata> nodes,
      Map<RelationPath, CollectionMetadata> collections) {
    this.nodes = new IdentityHashMap<>(nodes);
    this.collections = Map.copyOf(collections);
  }

  public Optional<NodeMetadata> metadata(Node node) {
    return Optional.ofNullable(nodes.get(node));
  }

  public Map<RelationPath, CollectionMetadata> collections() {
    return collections;
  }

  public static SourceStructure fromStructured(Object source, Hierarchy hierarchy, String syntheticRoot) {
    Builder builder = new Builder();
    if (source == null || hierarchy == null || hierarchy.getRoot() == null) return builder.build();
    Node root = hierarchy.getRoot();
    if (source instanceof Map<?, ?> object && object.size() == 1) {
      Map.Entry<?, ?> entry = object.entrySet().iterator().next();
      String name = entry.getKey().toString();
      Object value = entry.getValue();
      RelationPath path = RelationPath.root().member(name);
      if (value instanceof List<?> array) builder.pairCollection(array, root.getChildren(), path, Optional.of(name), false);
      else builder.pairValue(value, root, path, Optional.empty(), Optional.of(name));
    } else if (source instanceof List<?> array) {
      builder.mark(root, RelationPath.root(), NodeKind.STRUCTURAL_CONTAINER, Optional.empty(), Optional.empty());
      builder.pairCollection(array, root.getChildren(), RelationPath.root(), Optional.empty(), true);
    } else {
      builder.pairValue(source, root, RelationPath.root(), Optional.empty(), Optional.of(syntheticRoot));
    }
    return builder.build();
  }

  public static SourceStructure fromHierarchy(Hierarchy hierarchy) {
    Builder builder = new Builder();
    if (hierarchy == null || hierarchy.getRoot() == null) return builder.build();
    Node root = hierarchy.getRoot();
    boolean anonymousRecords = !root.getChildren().isEmpty()
        && root.getChildren().stream().allMatch(child -> child.isArrayItem() && "item".equals(child.getName()));
    if (anonymousRecords) {
      builder.mark(root, RelationPath.root(), NodeKind.STRUCTURAL_CONTAINER, Optional.empty(), Optional.empty());
      builder.pairHierarchyCollection(root.getChildren(), RelationPath.root(), Optional.empty(), true);
    } else {
      RelationPath rootPath = root.getName() == null || root.getName().isEmpty()
          ? RelationPath.root()
          : RelationPath.root().member(root.getName());
      builder.pairHierarchyValue(root, rootPath, Optional.empty(), Optional.ofNullable(root.getName()));
    }
    return builder.build();
  }

  /** Builds source facts for format-defined anonymous records such as CSV rows. */
  public static SourceStructure fromAnonymousRecords(Hierarchy hierarchy) {
    Builder builder = new Builder();
    if (hierarchy == null || hierarchy.getRoot() == null) return builder.build();
    Node root = hierarchy.getRoot();
    builder.mark(root, RelationPath.root(), NodeKind.STRUCTURAL_CONTAINER,
        Optional.empty(), Optional.empty());
    builder.pairHierarchyCollection(
        root.getChildren(), RelationPath.root(), Optional.empty(), true);
    return builder.build();
  }

  private static final class Builder {
    private final IdentityHashMap<Node, NodeMetadata> nodes = new IdentityHashMap<>();
    private final Map<RelationPath, CollectionMetadata> collections = new LinkedHashMap<>();

    SourceStructure build() {
      return new SourceStructure(nodes, collections);
    }

    void pairCollection(
        List<?> sourceItems,
        List<Node> hierarchyItems,
        RelationPath collectionPath,
        Optional<String> declaredName,
        boolean anonymous) {
      collections.putIfAbsent(collectionPath,
          new CollectionMetadata(collectionPath, declaredName, anonymous, sourceItems.isEmpty()));
      int count = Math.min(sourceItems.size(), hierarchyItems.size());
      for (int index = 0; index < count; index++) {
        Object value = sourceItems.get(index);
        Node node = hierarchyItems.get(index);
        RelationPath itemPath = collectionPath.each();
        NodeKind kind = value instanceof List<?> ? NodeKind.STRUCTURAL_CONTAINER
            : value instanceof Map<?, ?> ? NodeKind.OBJECT : NodeKind.SCALAR;
        mark(node, itemPath, kind, Optional.of(collectionPath), declaredName);
        if (value instanceof Map<?, ?> object) pairMap(object, node, itemPath);
        else if (value instanceof List<?> nested) {
          pairCollection(nested, node.getChildren(), collectionPath.position(index), Optional.empty(), true);
        }
      }
    }

    void pairValue(
        Object value,
        Node node,
        RelationPath valuePath,
        Optional<RelationPath> owningCollection,
        Optional<String> declaredName) {
      NodeKind kind = value instanceof Map<?, ?> ? NodeKind.OBJECT
          : value instanceof List<?> ? NodeKind.STRUCTURAL_CONTAINER : NodeKind.SCALAR;
      mark(node, valuePath, kind, owningCollection, declaredName);
      if (value instanceof Map<?, ?> object) pairMap(object, node, valuePath);
      else if (value instanceof List<?> array) {
        pairCollection(array, node.getChildren(), valuePath, declaredName, declaredName.isEmpty());
      }
    }

    void pairMap(Map<?, ?> object, Node parent, RelationPath parentPath) {
      Map<String, Integer> offsets = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : object.entrySet()) {
        String name = entry.getKey().toString();
        Object value = entry.getValue();
        RelationPath childPath = parentPath.member(name);
        List<Node> matching = parent.getChildren().stream().filter(child -> name.equals(child.getName())).toList();
        int offset = offsets.getOrDefault(name, 0);
        if (value instanceof List<?> array) {
          List<Node> items = matching.subList(Math.min(offset, matching.size()), matching.size());
          pairCollection(array, items, childPath, Optional.of(name), false);
          offsets.put(name, offset + items.size());
        } else if (offset < matching.size()) {
          pairValue(value, matching.get(offset), childPath, Optional.empty(), Optional.of(name));
          offsets.put(name, offset + 1);
        }
      }
    }

    void pairHierarchyCollection(
        List<Node> items,
        RelationPath path,
        Optional<String> declaredName,
        boolean anonymous) {
      collections.putIfAbsent(path, new CollectionMetadata(path, declaredName, anonymous, items.isEmpty()));
      for (Node node : items) {
        mark(node, path.each(), scalar(node) ? NodeKind.SCALAR : NodeKind.OBJECT,
            Optional.of(path), declaredName);
        pairHierarchyChildren(node, path.each());
      }
    }

    void pairHierarchyValue(
        Node node,
        RelationPath path,
        Optional<RelationPath> owningCollection,
        Optional<String> declaredName) {
      mark(node, path, scalar(node) ? NodeKind.SCALAR : NodeKind.OBJECT, owningCollection, declaredName);
      pairHierarchyChildren(node, path);
    }

    void pairHierarchyChildren(Node parent, RelationPath parentPath) {
      Map<String, List<Node>> groups = new LinkedHashMap<>();
      for (Node child : parent.getChildren()) {
        if (!child.isAttribute()) groups.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
      }
      for (Map.Entry<String, List<Node>> group : groups.entrySet()) {
        RelationPath path = parentPath.member(group.getKey());
        boolean repeated = group.getValue().size() > 1 || group.getValue().stream().anyMatch(Node::isArrayItem);
        if (repeated && group.getValue().stream().anyMatch(node -> !scalar(node))) {
          pairHierarchyCollection(group.getValue(), path, Optional.of(group.getKey()), false);
        } else {
          for (Node node : group.getValue()) pairHierarchyValue(node, path, Optional.empty(), Optional.of(group.getKey()));
        }
      }
    }

    void mark(
        Node node,
        RelationPath path,
        NodeKind kind,
        Optional<RelationPath> owningCollection,
        Optional<String> declaredName) {
      nodes.put(node, new NodeMetadata(path, kind, owningCollection, declaredName));
    }

    static boolean scalar(Node node) {
      return node.hasValue() && node.getChildren().stream().noneMatch(child -> !child.isAttribute());
    }
  }
}
