package blater.nql.parser.script;

import blater.nql.domain.HierarchyPath;
import blater.nql.domain.KeyedPath;
import blater.nql.domain.MappingCondition;
import blater.nql.domain.MappingPlan;
import blater.nql.domain.OutputField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles the immutable SELECT model into executable SQL and its output mapping plan. */
final class SelectBlueprintCompiler {
  private static final String SELECT_BRANCH_COLUMN = "hiql_select_branch";
  private static final String SELECT_BRANCH_VALUE_PREFIX = "select_branch_";

  private SelectBlueprintCompiler() {
  }

  static SelectBlueprint.Compiled compile(
      List<SelectBlueprint.Branch> branches,
      List<SelectBlueprint.OrderItem> orderItems,
      List<SelectBlueprint.StructureKey> explicitKeys,
      List<SelectBlueprint.StructureKey> inferredKeys) {

    List<SelectBlueprint.StructureKey> keys = mergeKeys(explicitKeys, inferredKeys);
    List<InternalExpression> internalExpressions = internalExpressions(keys, orderItems);
    List<Integer> outputCounts = branches.stream().map(branch -> branch.items().size()).toList();
    StringBuilder sql = new StringBuilder();
    List<OutputField> fields = new ArrayList<>();
    for (int index = 0; index < branches.size(); index++) {
      if (index > 0) {
        sql.append(" union all ");
      }
      emitBranchSql(sql, fields, branches.get(index), index, outputCounts, internalExpressions);
    }
    sql.append(genericOrderBy(orderItems, internalExpressions));
    return new SelectBlueprint.Compiled(sql.toString(), mappingPlan(fields, keys, internalExpressions));
  }

  private static List<SelectBlueprint.StructureKey> mergeKeys(
      List<SelectBlueprint.StructureKey> explicitKeys,
      List<SelectBlueprint.StructureKey> inferredKeys) {

    List<SelectBlueprint.StructureKey> keys = new ArrayList<>(explicitKeys);
    for (SelectBlueprint.StructureKey inferred : inferredKeys) {
      boolean explicit = explicitKeys.stream().anyMatch(key -> key.path().equals(inferred.path()));
      if (!explicit) {
        keys.add(inferred);
      }
    }
    return keys;
  }

  private static List<InternalExpression> internalExpressions(
      List<SelectBlueprint.StructureKey> keys,
      List<SelectBlueprint.OrderItem> orderItems) {

    Map<String, InternalExpression> expressions = new LinkedHashMap<>();
    for (SelectBlueprint.StructureKey key : keys) {
      if (key.keyExpressions() instanceof SelectBlueprint.CommonKeyExpressions common) {
        for (String expression : common.expressions()) {
          expressions.putIfAbsent(expression, new InternalExpression(expression, key.path(), null, -1));
        }
      } else {
        addBranchKeyExpressions(expressions, key);
      }
    }
    for (SelectBlueprint.OrderItem item : orderItems) {
      expressions.putIfAbsent(item.expression(), new InternalExpression(item.expression(), null, null, -1));
    }
    return new ArrayList<>(expressions.values());
  }

  private static void addBranchKeyExpressions(
      Map<String, InternalExpression> expressions,
      SelectBlueprint.StructureKey key) {

    List<String> names = key.internalExpressionNames();
    for (int component = 0; component < names.size(); component++) {
      String expression = names.get(component);
      expressions.put(expression, new InternalExpression(expression, key.path(), key, component));
    }
  }

  private static void emitBranchSql(
      StringBuilder sql,
      List<OutputField> fields,
      SelectBlueprint.Branch branch,
      int branchIndex,
      List<Integer> outputCounts,
      List<InternalExpression> internalExpressions) {

    String branchValue = SELECT_BRANCH_VALUE_PREFIX + branchIndex;
    StringBuilder selectItems = new StringBuilder();
    int resultColumn = appendInternalExpressions(selectItems, branch, branchIndex, internalExpressions);
    resultColumn = appendOutputColumns(
        selectItems, fields, branch, branchIndex, outputCounts, branchValue, resultColumn);
    selectItems.append(", '").append(branchValue).append("' as ").append(quoteAlias(SELECT_BRANCH_COLUMN));
    sql.append("select ").append(selectItems);
    if (branch.sqlTail() != null && !branch.sqlTail().isEmpty()) {
      sql.append(' ').append(branch.sqlTail());
    }
  }

  private static int appendInternalExpressions(
      StringBuilder selectItems,
      SelectBlueprint.Branch branch,
      int branchIndex,
      List<InternalExpression> internalExpressions) {

    int resultColumn = 1;
    for (InternalExpression internal : internalExpressions) {
      String expression = internalExpression(branch, branchIndex, internal);
      appendItem(selectItems, resultColumn++, expression == null ? "null" : expression);
    }
    return resultColumn;
  }

  private static String internalExpression(
      SelectBlueprint.Branch branch,
      int branchIndex,
      InternalExpression internal) {

    if (internal.path() != null && !branch.mapsPath(internal.path())) {
      return "null";
    }
    if (internal.branchKey() != null) {
      return internal.branchKey().expressionFor(branchIndex, internal.component());
    }
    return branch.expressionText(internal.expression());
  }

  private static int appendOutputColumns(
      StringBuilder selectItems,
      List<OutputField> fields,
      SelectBlueprint.Branch branch,
      int branchIndex,
      List<Integer> outputCounts,
      String branchValue,
      int resultColumn) {

    for (int index = 0; index < outputCounts.size(); index++) {
      if (index == branchIndex) {
        for (SelectBlueprint.SelectItem item : branch.items()) {
          appendItem(selectItems, resultColumn, item.expression());
          recordOutputField(fields, branchValue, item, resultColumn++);
        }
      } else {
        for (int ignored = 0; ignored < outputCounts.get(index); ignored++) {
          appendItem(selectItems, resultColumn++, "null");
        }
      }
    }
    return resultColumn;
  }

  private static void appendItem(StringBuilder buffer, int resultColumn, String expression) {
    if (resultColumn > 1) {
      buffer.append(", ");
    }
    buffer.append(expression).append(" as ").append(quoteAlias("col" + resultColumn));
  }

  private static void recordOutputField(
      List<OutputField> fields,
      String branchValue,
      SelectBlueprint.SelectItem item,
      int resultColumn) {

    if (item.outputPath() == null) {
      return;
    }
    fields.add(new OutputField(
        item.outputPath(),
        "col" + resultColumn,
        item.appendText(),
        List.of(MappingCondition.eq(SELECT_BRANCH_COLUMN, branchValue)),
        item.absentOnNull()));
  }

  private static String genericOrderBy(
      List<SelectBlueprint.OrderItem> orderItems,
      List<InternalExpression> internalExpressions) {

    if (orderItems.isEmpty()) {
      return "";
    }
    StringBuilder buffer = new StringBuilder(" order by ");
    for (int index = 0; index < orderItems.size(); index++) {
      if (index > 0) {
        buffer.append(", ");
      }
      SelectBlueprint.OrderItem item = orderItems.get(index);
      buffer.append(quoteAlias("col" + (indexOfExpression(internalExpressions, item.expression()) + 1)));
      if (item.direction() != null) {
        buffer.append(' ').append(item.direction());
      }
    }
    return buffer.toString();
  }

  private static int indexOfExpression(List<InternalExpression> expressions, String expression) {
    for (int index = 0; index < expressions.size(); index++) {
      if (expressions.get(index).expression().equals(expression)) {
        return index;
      }
    }
    throw new IllegalStateException("Missing internal expression: " + expression);
  }

  private static MappingPlan mappingPlan(
      List<OutputField> fields,
      List<SelectBlueprint.StructureKey> keys,
      List<InternalExpression> internalExpressions) {

    Map<String, String> internalColumns = new LinkedHashMap<>();
    for (int index = 0; index < internalExpressions.size(); index++) {
      internalColumns.put(internalExpressions.get(index).expression(), "col" + (index + 1));
    }
    List<KeyedPath> keyedPaths = new ArrayList<>();
    for (SelectBlueprint.StructureKey key : keys) {
      List<String> columns = key.internalExpressionNames().stream().map(internalColumns::get).toList();
      keyedPaths.add(new KeyedPath(key.identityPath(), key.placement(), columns, key.origin()));
    }
    return new MappingPlan(fields, keyedPaths);
  }

  private static String quoteAlias(String alias) {
    return "\"" + alias + "\"";
  }

  private record InternalExpression(
      String expression,
      HierarchyPath path,
      SelectBlueprint.StructureKey branchKey,
      int component) {
  }
}
