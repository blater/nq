package blater.nql.domain;

import blater.nql.runner.sql.domain.QueryResultRow;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Maintains stable keyed and singleton child identity across mapped rows. */
final class HierarchyKeyIndex {
  private final IdentityHashMap<Node, Map<HierarchyPath, ChildBucket>> children =
      new IdentityHashMap<>();

  Node singletonChild(Node parent, HierarchyPath path) {
    ChildBucket bucket = bucket(parent, path);
    if (bucket.singleton == null) {
      bucket.singleton = new Node(path.getTerminalNodeName());
      parent.addNode(bucket.singleton);
    }
    return bucket.singleton;
  }

  Node keyedChild(Node parent, HierarchyPath path, KeyTuple key) {
    ChildBucket bucket = bucket(parent, path);
    return bucket.keyed.computeIfAbsent(key, ignored -> {
      Node child = new Node(path.getTerminalNodeName());
      child.setArrayItem(true);
      parent.addNode(child);
      return child;
    });
  }

  Node keyedAnonymousChild(Node parent, HierarchyPath path, KeyTuple key) {
    ChildBucket bucket = bucket(parent, path);
    return bucket.keyed.computeIfAbsent(key, ignored -> {
      Node child = new Node("");
      parent.addNode(child);
      return child;
    });
  }

  KeyState keyState(KeyedPath keyedPath, QueryResultRow row) {
    long nullCount = keyedPath.sourceColumns().stream().filter(row::isNull).count();
    if (nullCount == keyedPath.sourceColumns().size()) {
      return KeyState.ABSENT;
    }
    return nullCount == 0 ? KeyState.PRESENT : KeyState.PARTIAL;
  }

  KeyTuple keyTuple(KeyedPath keyedPath, QueryResultRow row) {
    return new KeyTuple(keyedPath.sourceColumns().stream()
        .map(column -> StructureKeyValue.normalize(row.getValue(column)))
        .toList());
  }

  private ChildBucket bucket(Node parent, HierarchyPath path) {
    return children
        .computeIfAbsent(parent, ignored -> new HashMap<>())
        .computeIfAbsent(path, ignored -> new ChildBucket());
  }

  enum KeyState { ABSENT, PARTIAL, PRESENT }

  record KeyTuple(List<Object> values) {
    KeyTuple {
      values = List.copyOf(values);
    }
  }

  private static final class ChildBucket {
    private Node singleton;
    private final Map<KeyTuple, Node> keyed = new HashMap<>();
  }
}
