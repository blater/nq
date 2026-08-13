package blater.nq.parser.script;

import blater.nq.domain.HierarchyPath;
import blater.nq.domain.KeyOrigin;
import blater.nq.domain.KeyedPath;
import blater.nq.domain.MappingCondition;
import blater.nq.domain.MappingPlan;
import blater.nq.domain.OutputField;
import blater.nq.domain.RepetitionPlacement;

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
      List<String> columns = key.internalExpressionNames().stream().map(internalColumns::get).toList();
      keyedPaths.add(new KeyedPath(key.identityPath(), key.placement(), columns, key.origin()));
    }

    MappingPlan plan = new MappingPlan(fields, keyedPaths);
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
      if (key.keyExpressions() instanceof CommonKeyExpressions common) {
        for (String expression : common.expressions()) {
          expressions.putIfAbsent(expression, new InternalExpression(expression, key.path(), null, -1));
        }
      } else {
        for (int component = 0; component < key.internalExpressionNames().size(); component++) {
          String expression = key.internalExpressionNames().get(component);
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

  private static String quoteAlias(String alias) {
    return "\"" + alias + "\"";
  }

  public record Compiled(String sql, MappingPlan plan) {
  }

  public record Branch(List<SelectItem> items, String sqlTail, QueryShape queryShape) {
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
      boolean absentOnNull,
      QueryShape.ExpressionFacts expressionFacts) {
  }

  public record OrderItem(String expression, String direction) {
  }

  public sealed interface KeyExpressions permits CommonKeyExpressions, BranchKeyExpressions {
  }

  public record CommonKeyExpressions(
      List<String> expressions,
      List<QueryShape.ExpressionFacts> expressionFacts) implements KeyExpressions {

    public CommonKeyExpressions(List<String> expressions) {
      this(expressions, List.of());
    }

    public CommonKeyExpressions {
      expressions = List.copyOf(expressions);
      if (expressions.isEmpty()) throw new IllegalArgumentException("A structure key requires expressions.");
      expressionFacts = expressionFacts == null ? List.of() : List.copyOf(expressionFacts);
      if (!expressionFacts.isEmpty() && expressionFacts.size() != expressions.size()) {
        throw new IllegalArgumentException("Structure key expression facts must match expression arity.");
      }
    }
  }

  public record BranchKeyExpressions(Map<Integer, List<String>> expressions) implements KeyExpressions {
    public BranchKeyExpressions {
      if (expressions.isEmpty()) throw new IllegalArgumentException("A branch key requires expressions.");
      Map<Integer, List<String>> copied = new LinkedHashMap<>();
      expressions.forEach((branch, values) -> copied.put(branch, List.copyOf(values)));
      int components = copied.values().iterator().next().size();
      if (components == 0 || copied.values().stream().anyMatch(values -> values.size() != components)) {
        throw new IllegalArgumentException("Hierarchy-union inferred keys must have compatible arity.");
      }
      expressions = Map.copyOf(copied);
    }

    int componentCount() {
      return expressions.values().iterator().next().size();
    }
  }

  public record StructureKey(
      HierarchyPath identityPath,
      RepetitionPlacement placement,
      KeyExpressions keyExpressions,
      KeyOrigin origin) {

    public StructureKey(HierarchyPath path, List<String> expressions, KeyOrigin origin) {
      this(
          path,
          origin == KeyOrigin.INFERRED
              ? RepetitionPlacement.anonymous(path.parent())
              : RepetitionPlacement.named(),
          new CommonKeyExpressions(expressions),
          origin);
    }

    public StructureKey {
      if (identityPath == null) throw new IllegalArgumentException("A structure key requires an identity path.");
      if (placement == null) throw new IllegalArgumentException("A structure key requires repetition placement.");
      if (keyExpressions == null) throw new IllegalArgumentException("A structure key requires expressions.");
      origin = origin == null ? KeyOrigin.EXPLICIT : origin;
      if (origin == KeyOrigin.EXPLICIT && !(placement instanceof RepetitionPlacement.NamedItem)) {
        throw new IllegalArgumentException("Explicit keys must repeat their named identity path.");
      }
    }

    public HierarchyPath path() {
      return identityPath;
    }

    public static StructureKey inferred(
        HierarchyPath path,
        RepetitionPlacement placement,
        List<String> expressions) {
      return new StructureKey(path, placement, new CommonKeyExpressions(expressions), KeyOrigin.INFERRED);
    }

    public static StructureKey inferredBranches(
        HierarchyPath path,
        RepetitionPlacement placement,
        Map<Integer, List<String>> branchExpressions) {
      return new StructureKey(
          path, placement, new BranchKeyExpressions(branchExpressions), KeyOrigin.INFERRED);
    }

    List<String> internalExpressionNames() {
      if (keyExpressions instanceof CommonKeyExpressions common) return common.expressions();
      BranchKeyExpressions branches = (BranchKeyExpressions) keyExpressions;
      String prefix = "__nq_inferred_" + String.join("_", identityPath.getPathParts()) + "_";
      return java.util.stream.IntStream.range(0, branches.componentCount() + 1)
          .mapToObj(index -> prefix + index)
          .toList();
    }

    String expressionFor(int branch, int component) {
      if (!(keyExpressions instanceof BranchKeyExpressions branches)) return null;
      if (component == 0) return "'" + SELECT_BRANCH_VALUE_PREFIX + branch + "'";
      List<String> values = branches.expressions().get(branch);
      int branchComponent = component - 1;
      return values == null || branchComponent >= values.size() ? null : values.get(branchComponent);
    }
  }

  private record InternalExpression(
      String expression,
      HierarchyPath path,
      StructureKey branchKey,
      int component) {
  }
}
