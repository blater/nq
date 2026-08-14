package blater.nq.domain;

import blater.nq.util.Log;

import java.util.Objects;
import java.util.Set;

/** Applies scalar field values to resolved hierarchy nodes. */
final class HierarchyValueWriter {
  private HierarchyValueWriter() {
  }

  static void writeResolvedValue(
      Node parent,
      OutputField field,
      String value,
      boolean nullValue,
      ScalarKind scalarKind,
      MappingPlan plan,
      Set<HierarchyPath> warnedConflictPaths) {
    String name = field.getPath().getTerminalNodeName();
    int existingIndex = lastChildIndex(parent, name);
    if (field.getAppendText() != null) {
      appendValue(parent, existingIndex, field, value, nullValue);
      return;
    }
    if (existingIndex < 0) {
      parent.addNode(valueNode(field, value, nullValue, scalarKind));
      return;
    }

    Node existing = parent.getChildren().get(existingIndex);
    if (sameValue(existing, value, nullValue, scalarKind)) {
      return;
    }
    KeyedPath inferredKey = nearestKeyedPath(field.getPath().parent(), plan);
    if (inferredKey != null && inferredKey.inferred()) {
      warnInferredConflict(field.getPath(), inferredKey, warnedConflictPaths);
      return;
    }
    throw new IllegalStateException("Conflicting values for output path: " + field.getPath());
  }

  static void writeTerminalKeyedValue(
      Node target,
      OutputField field,
      String value,
      boolean nullValue,
      ScalarKind scalarKind,
      KeyedPath keyedPath,
      Set<HierarchyPath> warnedConflictPaths) {
    if (field.getAppendText() != null) {
      appendTerminalValue(target, field, value, nullValue);
      return;
    }
    if (Node.isNull(target)) {
      target.setValue(value);
      target.setNullValue(nullValue);
      target.setScalarKind(scalarKind);
      target.setAttribute(field.isAttribute());
      return;
    }
    if (sameValue(target, value, nullValue, scalarKind)) {
      return;
    }
    if (keyedPath.inferred()) {
      warnInferredConflict(field.getPath(), keyedPath, warnedConflictPaths);
      return;
    }
    throw new IllegalStateException("Conflicting values for output path: " + field.getPath());
  }

  private static void appendValue(
      Node parent, int existingIndex, OutputField field, String value, boolean nullValue) {
    requireAppendValue(field, nullValue);
    if (existingIndex < 0) {
      parent.addNode(valueNode(field, value, false, ScalarKind.STRING));
      return;
    }
    Node existing = parent.getChildren().get(existingIndex);
    if (existing.isNull()) {
      throw new IllegalStateException("append mapping cannot combine a null value: " + field.getPath());
    }
    parent.replaceChild(existingIndex, valueNode(
        field, existing.getValue() + field.getAppendText() + value, false, ScalarKind.STRING));
  }

  private static void appendTerminalValue(
      Node target, OutputField field, String value, boolean nullValue) {
    requireAppendValue(field, nullValue);
    if (Node.isNull(target)) {
      target.setValue(value);
      target.setNullValue(false);
      target.setScalarKind(ScalarKind.STRING);
    } else {
      target.setValue(target.getValue() + field.getAppendText() + value);
      target.setScalarKind(ScalarKind.STRING);
    }
  }

  private static void requireAppendValue(OutputField field, boolean nullValue) {
    if (nullValue) {
      throw new IllegalStateException(
          "append mapping requires absent on null for path: " + field.getPath());
    }
  }

  private static boolean sameValue(
      Node node, String value, boolean nullValue, ScalarKind scalarKind) {
    return node.isNull() == nullValue
        && node.getScalarKind() == scalarKind
        && Objects.equals(node.getValue(), value);
  }

  private static KeyedPath nearestKeyedPath(HierarchyPath path, MappingPlan plan) {
    for (HierarchyPath current = path; current != null; current = current.parent()) {
      for (KeyedPath key : plan.getKeyedPaths()) {
        if (key.path().equals(current)) {
          return key;
        }
      }
    }
    return null;
  }

  private static void warnInferredConflict(
      HierarchyPath valuePath, KeyedPath keyedPath, Set<HierarchyPath> warnedConflictPaths) {
    if (!warnedConflictPaths.add(keyedPath.path())) {
      return;
    }
    Log.warn(
        "Inferred structure key [{}] coalesced conflicting value [{}]; keeping the first value. Possible data loss.",
        keyedPath.sourceColumns(), String.join(".", valuePath.getPathParts()));
  }

  private static int lastChildIndex(Node parent, String name) {
    for (int index = parent.getChildren().size() - 1; index >= 0; index--) {
      if (parent.getChildren().get(index).getName().equals(name)) {
        return index;
      }
    }
    return -1;
  }

  private static Node valueNode(
      OutputField field, String value, boolean nullValue, ScalarKind scalarKind) {
    Node node = new Node(field.getPath().getTerminalNodeName());
    node.setValue(value);
    node.setNullValue(nullValue);
    node.setScalarKind(scalarKind);
    node.setAttribute(field.isAttribute());
    return node;
  }
}
