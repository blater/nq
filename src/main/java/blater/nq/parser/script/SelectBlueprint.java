package blater.nq.parser.script;

import blater.nq.domain.HierarchyPath;
import blater.nq.domain.KeyOrigin;
import blater.nq.domain.MappingPlan;
import blater.nq.domain.RepetitionPlacement;

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
    return SelectBlueprintCompiler.compile(branches, orderItems, explicitKeys, inferredKeys);
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

}
