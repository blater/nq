package blater.nq.parser.script;

import blater.nq.domain.CorrelationRule;
import blater.nq.domain.HierarchyPath;
import blater.nq.domain.KeyOrigin;
import blater.nq.domain.KeyedPath;
import blater.nq.domain.MappingCondition;
import blater.nq.domain.MappingPlan;
import blater.nq.domain.OutputField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, connection-independent representation of a mapped SELECT.
 *
 * The parser uses it to render the current explicit structure. At execution time key inference
 * can render the same SELECT with additional hidden key projections.
 */
public final class SelectBlueprint {
  private static final String SELECT_BRANCH_COLUMN = "hiql_select_branch";
  private static final String SELECT_BRANCH_VALUE_PREFIX = "select_branch_";

  private final List<Branch> branches;
  private final List<OrderItem> orderItems;
  private final List<StructureKey> explicitKeys;

  public SelectBlueprint(
      List<Branch> branches,
      List<OrderItem> orderItems,
      List<StructureKey> explicitKeys) {
    this.branches = List.copyOf(branches);
    this.orderItems = List.copyOf(orderItems);
    this.explicitKeys = List.copyOf(explicitKeys);
  }

  public List<Branch> branches() {
    return branches;
  }

  public List<OrderItem> orderItems() {
    return orderItems;
  }

  public List<StructureKey> explicitKeys() {
    return explicitKeys;
  }

  public List<String> outputNames() {
    if (branches.isEmpty()) return List.of();
    return branches.getFirst().items().stream().map(SelectItem::name).toList();
  }

  public Compiled compile(List<StructureKey> inferredKeys) {
    List<StructureKey> keys = new ArrayList<>(explicitKeys);
    for (StructureKey inferred : inferredKeys) {
      boolean explicit = explicitKeys.stream().anyMatch(key -> key.path().equals(inferred.path()));
      if (!explicit) {
        keys.add(inferred);
      }
    }

    List<InternalExpression> internalExpressions = internalExpressions(keys, orderItems);
    List<Integer> nonKeyCounts = branches.stream().map(branch -> branch.items().size()).toList();
    StringBuilder sql = new StringBuilder();
    List<OutputField> fields = new ArrayList<>();
    for (int idx = 0; idx < branches.size(); idx++) {
      if (idx > 0) {
        sql.append(" union all ");
      }
      emitBranchSql(sql, fields, branches.get(idx), idx, nonKeyCounts, internalExpressions);
    }
    sql.append(genericOrderBy(orderItems, internalExpressions));

    Map<String, String> internalColumns = new LinkedHashMap<>();
    for (int idx = 0; idx < internalExpressions.size(); idx++) {
      internalColumns.put(internalExpressions.get(idx).expression(), "col" + (idx + 1));
    }
    List<KeyedPath> keyedPaths = new ArrayList<>();
    for (StructureKey key : keys) {
      List<String> columns = key.expressions().stream().map(internalColumns::get).toList();
      keyedPaths.add(new KeyedPath(key.path(), columns, key.origin()));
    }

    MappingPlan plan = new MappingPlan(fields, legacyCorrelationRules(orderItems, internalExpressions), keyedPaths);
    return new Compiled(sql.toString(), plan);
  }

  public List<HierarchyPath> objectPaths() {
    Map<String, HierarchyPath> paths = new LinkedHashMap<>();
    for (Branch branch : branches) {
      for (SelectItem item : branch.items()) {
        if (item.outputPath() == null) {
          continue;
        }
        HierarchyPath path = item.outputPath().parent();
        if (path != null) {
          paths.putIfAbsent(path.toString(), path);
        }
      }
    }
    return List.copyOf(paths.values());
  }

  private static List<InternalExpression> internalExpressions(
      List<StructureKey> keys,
      List<OrderItem> orderItems) {
    Map<String, InternalExpression> expressions = new LinkedHashMap<>();
    for (StructureKey key : keys) {
      if (key.branchExpressions().isEmpty()) {
        for (String expression : key.expressions()) {
          expressions.putIfAbsent(expression, new InternalExpression(expression, key.path(), null, -1));
        }
      } else {
        for (int component = 0; component < key.expressions().size(); component++) {
          String expression = key.expressions().get(component);
          expressions.put(expression, new InternalExpression(expression, key.path(), key, component));
        }
      }
    }
    for (OrderItem item : orderItems) {
      expressions.putIfAbsent(item.expression(), new InternalExpression(item.expression(), null, null, -1));
    }
    return new ArrayList<>(expressions.values());
  }

  private static void emitBranchSql(
      StringBuilder sql,
      List<OutputField> fields,
      Branch branch,
      int branchIndex,
      List<Integer> nonKeyCounts,
      List<InternalExpression> internalExpressions) {
    String branchValue = SELECT_BRANCH_VALUE_PREFIX + branchIndex;
    StringBuilder selectItems = new StringBuilder();
    int rsColNum = 1;
    for (InternalExpression internal : internalExpressions) {
      boolean mapsPath = internal.path() == null || branch.mapsPath(internal.path());
      String expression;
      if (!mapsPath) {
        expression = "null";
      } else if (internal.branchKey() != null) {
        expression = internal.branchKey().expressionFor(branchIndex, internal.component());
      } else {
        expression = branch.expressionText(internal.expression());
      }
      if (expression == null) expression = "null";
      appendItem(selectItems, rsColNum++, expression);
    }

    int columnNum = 0;
    for (int otherIdx = 0; otherIdx < nonKeyCounts.size(); otherIdx++) {
      if (otherIdx == branchIndex) {
        while (columnNum < branch.items().size()) {
          SelectItem item = branch.items().get(columnNum++);
          appendItem(selectItems, rsColNum, item.expression());
          recordOutputField(fields, branchValue, item, rsColNum++);
        }
      } else {
        for (int j = 0; j < nonKeyCounts.get(otherIdx); j++) {
          appendItem(selectItems, rsColNum++, "null");
        }
      }
    }
    selectItems.append(", '").append(branchValue).append("' as ").append(quoteAlias(SELECT_BRANCH_COLUMN));
    sql.append("select ").append(selectItems);
    if (branch.sqlTail() != null && !branch.sqlTail().isEmpty()) {
      sql.append(' ').append(branch.sqlTail());
    }
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
      SelectItem item,
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
      List<OrderItem> orderItems,
      List<InternalExpression> internalExpressions) {
    if (orderItems.isEmpty()) {
      return "";
    }
    StringBuilder buffer = new StringBuilder(" order by ");
    for (int idx = 0; idx < orderItems.size(); idx++) {
      if (idx > 0) {
        buffer.append(", ");
      }
      OrderItem item = orderItems.get(idx);
      buffer.append(quoteAlias("col" + (indexOfExpression(internalExpressions, item.expression()) + 1)));
      if (item.direction() != null) {
        buffer.append(' ').append(item.direction());
      }
    }
    return buffer.toString();
  }

  private static int indexOfExpression(List<InternalExpression> expressions, String expression) {
    for (int idx = 0; idx < expressions.size(); idx++) {
      if (expressions.get(idx).expression().equals(expression)) {
        return idx;
      }
    }
    throw new IllegalStateException("Missing internal expression: " + expression);
  }

  private static List<CorrelationRule> legacyCorrelationRules(
      List<OrderItem> orderItems,
      List<InternalExpression> internalExpressions) {
    List<CorrelationRule> rules = new ArrayList<>();
    for (OrderItem item : orderItems) {
      for (HierarchyPath path : item.legacyPaths()) {
        String column = "col" + (indexOfExpression(internalExpressions, item.expression()) + 1);
        rules.add(new CorrelationRule(path, List.of(MappingCondition.newValue(column))));
      }
    }
    return rules;
  }

  private static String quoteAlias(String alias) {
    return "\"" + alias + "\"";
  }

  public record Compiled(String sql, MappingPlan plan) {
  }

  public record Branch(List<SelectItem> items, String sqlTail) {
    public Branch {
      items = List.copyOf(items);
    }

    public boolean mapsPath(HierarchyPath path) {
      return items.stream().anyMatch(item -> item.outputPath() != null
          && (item.outputPath().equals(path) || item.outputPath().isBelow(path)));
    }

    String expressionText(String expression) {
      for (SelectItem item : items) {
        if (expression.equalsIgnoreCase(item.name()) || expression.equalsIgnoreCase(item.expression())) {
          return item.expression();
        }
      }
      return expression;
    }
  }

  public record SelectItem(
      String expression,
      String name,
      HierarchyPath outputPath,
      String appendText,
      boolean absentOnNull) {
  }

  public record OrderItem(String expression, String direction, List<HierarchyPath> legacyPaths) {
    public OrderItem {
      legacyPaths = List.copyOf(legacyPaths);
    }
  }

  public record StructureKey(
      HierarchyPath path,
      List<String> expressions,
      KeyOrigin origin,
      Map<Integer, List<String>> branchExpressions) {
    public StructureKey(HierarchyPath path, List<String> expressions, KeyOrigin origin) {
      this(path, expressions, origin, Map.of());
    }

    public StructureKey {
      expressions = List.copyOf(expressions);
      origin = origin == null ? KeyOrigin.EXPLICIT : origin;
      Map<Integer, List<String>> copied = new LinkedHashMap<>();
      branchExpressions.forEach((branch, values) -> copied.put(branch, List.copyOf(values)));
      branchExpressions = Map.copyOf(copied);
    }

    public static StructureKey inferred(HierarchyPath path, List<String> expressions) {
      return new StructureKey(path, expressions, KeyOrigin.INFERRED);
    }

    public static StructureKey inferredBranches(
        HierarchyPath path,
        Map<Integer, List<String>> branchExpressions) {
      int components = branchExpressions.values().stream().findFirst().map(List::size).orElse(0);
      if (components == 0 || branchExpressions.values().stream().anyMatch(values -> values.size() != components)) {
        throw new IllegalArgumentException("Hierarchy-union inferred keys must have compatible arity: " + path);
      }
      String prefix = "__nq_inferred_" + String.join("_", path.getPathParts()) + "_";
      List<String> identities = java.util.stream.IntStream.range(0, components)
          .mapToObj(index -> prefix + index)
          .toList();
      return new StructureKey(path, identities, KeyOrigin.INFERRED, branchExpressions);
    }

    String expressionFor(int branch, int component) {
      List<String> values = branchExpressions.get(branch);
      return values == null || component >= values.size() ? null : values.get(component);
    }
  }

  private record InternalExpression(
      String expression,
      HierarchyPath path,
      StructureKey branchKey,
      int component) {
  }
}
