package blater.nq.domain;

import blater.nq.runner.sql.domain.QueryResultRow;

import java.util.HashSet;
import java.util.Set;

/** Maps a result row into nodes after path and key resolution. */
final class HierarchyRowMapper {
  private final HierarchyNodeResolver nodeResolver;
  private final HierarchyKeyIndex keyIndex;
  private final Set<HierarchyPath> warnedConflictPaths = new HashSet<>();

  HierarchyRowMapper(HierarchyNodeResolver nodeResolver) {
    this.nodeResolver = nodeResolver;
    keyIndex = nodeResolver.keyIndex();
  }

  boolean readRow(
      Node root,
      Hierarchy.RootKind rootKind,
      MappingPlan plan,
      QueryResultRow row) {
    if (row == null) {
      return false;
    }
    HierarchyNodeResolver.RowContext rowContext = new HierarchyNodeResolver.RowContext();
    Node flatRow = flatRow(root, plan);
    for (OutputField field : plan.getFields()) {
      mapField(root, rootKind, plan, row, rowContext, flatRow, field);
    }
    return true;
  }

  private Node flatRow(Node root, MappingPlan plan) {
    if (!nodeResolver.flatRows(plan)) {
      return null;
    }
    Node row = new Node("");
    root.addNode(row);
    return row;
  }

  private void mapField(
      Node root,
      Hierarchy.RootKind rootKind,
      MappingPlan plan,
      QueryResultRow row,
      HierarchyNodeResolver.RowContext rowContext,
      Node flatRow,
      OutputField field) {
    if (!conditionsMatch(field, row)) {
      return;
    }
    boolean nullValue = row.isNull(field.getSourceColumn());
    if (nullValue && field.isAbsentOnNull()) {
      return;
    }
    Node parent = field.getPath().parent() == null && flatRow != null
        ? flatRow
        : nodeResolver.resolveParent(
            root, rootKind, plan, field.getPath().parent(), row, rowContext);
    if (parent == null) {
      return;
    }
    writeField(parent, plan, row, field, nullValue);
  }

  private void writeField(
      Node parent,
      MappingPlan plan,
      QueryResultRow row,
      OutputField field,
      boolean nullValue) {
    String value = row.getStringValue(field.getSourceColumn());
    KeyedPath terminalKey = nodeResolver.keyedPath(plan, field.getPath());
    if (terminalKey == null) {
      HierarchyValueWriter.writeResolvedValue(
          parent, field, value, nullValue, plan, warnedConflictPaths);
      return;
    }
    HierarchyKeyIndex.KeyState state = keyIndex.keyState(terminalKey, row);
    if (state == HierarchyKeyIndex.KeyState.ABSENT) {
      return;
    }
    if (state == HierarchyKeyIndex.KeyState.PARTIAL) {
      if (!terminalKey.inferred()) {
        throw new IllegalStateException("Partially null structure key: " + field.getPath());
      }
      return;
    }
    Node target = keyIndex.keyedChild(
        parent, field.getPath(), keyIndex.keyTuple(terminalKey, row));
    HierarchyValueWriter.writeTerminalKeyedValue(
        target, field, value, nullValue, terminalKey, warnedConflictPaths);
  }

  private static boolean conditionsMatch(OutputField field, QueryResultRow row) {
    return field.getConditions().stream().allMatch(condition -> Evaluator.evaluate(condition, row));
  }
}
