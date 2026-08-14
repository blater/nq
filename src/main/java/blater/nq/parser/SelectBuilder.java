package blater.nq.parser;

import blater.nq.core.parser.HiQLParser;
import blater.nq.domain.HierarchyPath;
import blater.nq.domain.MappingPlan;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.SelectBlueprint;
import blater.nq.util.Log;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;

import static blater.nq.util.ValueUtil.hasValue;

/*
 * Responsibility: Builds parsed SELECT statements, including query
 * SQL text, hierarchy mappings, property capture, and XML hints.
 */
final class SelectBuilder {

  private SelectBuilder() {
  }

  static NestStatement buildSelect(HiQLParser.SelectStatementContext ctx)
  {
    if (! validateUsingPlacement(ctx))
      Log.fatal(HiqlSyntaxException.class, "using metadata is only valid on the first hierarchy union branch.");

    UsingInfo using = readUsing(ctx.selectBranch(0).usingClause());
    List<Branch> branches = readBranches(ctx);
    validateBranchRoots(branches);
    boolean hasHierarchyFields = branches.stream().anyMatch(Branch::hasHierarchyFields);

    if (!hasHierarchyFields && using.hasValues()) {
      Log.fatal(HiqlSyntaxException.class, "using metadata requires at least one hierarchy mapping alias.");
    }


    if (!hasHierarchyFields) {
      SelectBlueprint blueprint = new SelectBlueprint(
          branches.stream().map(SelectBuilder::toBlueprintBranch).toList(),
          List.of(),
          List.of());
      return NestStatement.select(
          ParseUtils.textOf(ctx).trim(),
          new MappingPlan(),
          using.namespace,
          blueprint
      );
    }

    List<OrderItem> orderItems = readOrderItems(ctx.orderByClause());
    List<StructureItem> structureItems = readStructureItems(ctx.structureClause(), branches);
    SelectBlueprint blueprint = new SelectBlueprint(
        branches.stream().map(SelectBuilder::toBlueprintBranch).toList(),
        orderItems.stream().map(item -> new SelectBlueprint.OrderItem(
            item.expression, item.direction)).toList(),
        structureItems.stream().map(item -> new SelectBlueprint.StructureKey(
            item.path,
            blater.nq.domain.RepetitionPlacement.named(),
            new SelectBlueprint.CommonKeyExpressions(item.keyExpressions, item.expressionFacts),
            blater.nq.domain.KeyOrigin.EXPLICIT)).toList());
    SelectBlueprint.Compiled compiled = blueprint.compile(List.of());
    return NestStatement.select(compiled.sql(), compiled.plan(), using.namespace, blueprint);
  }

  private static SelectBlueprint.Branch toBlueprintBranch(Branch branch) {
    return new SelectBlueprint.Branch(
        branch.items.stream().map(item -> new SelectBlueprint.SelectItem(
            item.text,
            item.name,
            item.outputPath,
            item.appendText,
            item.absentOnNull,
            item.expressionFacts)).toList(),
        branch.sqlTail,
        branch.queryShape);
  }


  private static boolean validateUsingPlacement(HiQLParser.SelectStatementContext ctx) {
    boolean isValid = true;

    for (int idx = 1; idx < ctx.selectBranch().size(); idx++) {
      if (ctx.selectBranch(idx).usingClause() != null) {
        isValid = false;
        break;
      }
    }
    return isValid;
  }

  private static void validateBranchRoots(List<Branch> branches) {
    String root = null;
    for (Branch branch : branches) {
      for (SelectItem item : branch.items) {
        if (item.outputPath == null)
          continue;
        String itemRoot = item.outputPath.getRootName();
        if (root == null) {
          root = itemRoot;
        } else if (!root.equals(itemRoot)) {
          Log.fatal(HiqlSyntaxException.class, "hierarchy union branches must share one document wrapper.");
        }
      }
    }
  }

  private static UsingInfo readUsing(HiQLParser.UsingClauseContext ctx) {
    UsingInfo info = new UsingInfo();
    if (ctx == null) return info;
    for (HiQLParser.UsingItemContext item : ctx.usingItem()) {
      if (item.K_SCHEMA() != null) {
        info.schemaOrRootPresent = true;
      } else if (item.K_NAMESPACE() != null) {
        if (item.STRING() != null) {
          info.namespace = ParseUtils.unquoteString(item.STRING().getText());
        } else {
          info.namespace = ParseUtils.unquoteIdentifier(item.QUOTED_IDENTIFIER().getText());
        }
      } else if (item.K_XMLROOT() != null) {
        info.schemaOrRootPresent = true;
      }
    }
    return info;
  }

  private static List<Branch> readBranches(HiQLParser.SelectStatementContext ctx) {
    List<Branch> branches = new ArrayList<>();
    for (HiQLParser.SelectBranchContext branchCtx : ctx.selectBranch()) {
      Branch branch = new Branch();
      for (HiQLParser.SelectItemContext itemCtx : branchCtx.selectItem()) {
        SelectItem item = readSelectItem(itemCtx);
        branch.items.add(item);
      }
      if (branchCtx.sqlTail() != null) {
        branch.sqlTail = ParseUtils.textOf(branchCtx.sqlTail());
      }
      branch.queryShape = QueryShapeExtractor.extract(branchCtx);
      branches.add(branch);
    }
    return branches;
  }

  private static SelectItem readSelectItem(HiQLParser.SelectItemContext ctx) {
    SelectItem selectItem = null;
    List<Token> exprTokens = SelectTokenReader.tokensIn(ctx.selectExpr());
    String exprText = SelectTokenReader.joinText(exprTokens);
    String alias = ctx.sqlAlias() == null ? null : ParseUtils.unquoteIdentifier(ctx.sqlAlias().name().getText());

    String name = alias != null ? alias : SelectTokenReader.trailingIdentifier(exprTokens);
    if (ctx.mappingAlias() != null) {
      String cmd = null;
      String cmdArg = null;
      HiQLParser.MappingAliasContext mapping = ctx.mappingAlias();
      var path = PathContextMapper.toHierarchyPath(mapping.path());
      var absentOnNull = mapping.nullOutputPolicy() != null;
      if (hasValue(mapping.name())) {
        cmd = mapping.name(0).getText();
        cmdArg = mapping.name().size() > 1 ? mapping.name(1).getText() : null;
      }
      selectItem = new SelectItem(exprText, name, path, absentOnNull, cmd, cmdArg);
    } else {
      selectItem = new SelectItem(exprText, name);
    }
    selectItem.expressionFacts = QueryShapeExtractor.expressionFacts(ctx.selectExpr());

    return selectItem;
  }

  private static List<OrderItem> readOrderItems(HiQLParser.OrderByClauseContext ctx) {
    List<OrderItem> items = new ArrayList<>();
    if (ctx == null)
      return items;

    for (HiQLParser.OrderItemContext itemCtx : ctx.orderItem()) {
      OrderItem orderBy = new OrderItem();
      orderBy.expression = ParseUtils.textOf(itemCtx.orderExpr()).trim();
      if (itemCtx.K_DESC() != null) {
        orderBy.direction = "desc";
      } else if (itemCtx.K_ASC() != null) {
        orderBy.direction = "asc";
      }
      orderBy.expressionFacts = QueryShapeExtractor.expressionFacts(itemCtx.orderExpr());
      items.add(orderBy);
    }
    return items;
  }

  private static List<StructureItem> readStructureItems(
      HiQLParser.StructureClauseContext ctx, List<Branch> branches) {
    List<StructureItem> items = new ArrayList<>();
    if (ctx == null)
      return items;

    for (HiQLParser.StructureItemContext itemCtx : ctx.structureItem()) {
      HierarchyPath path = PathContextMapper.toHierarchyPath(itemCtx.path());
      if (path.isAttribute())
        Log.fatal(HiqlSyntaxException.class, "structure keys must target object paths, not attributes: " + path);
      if (items.stream().anyMatch(item -> item.path.equals(path)))
        Log.fatal(HiqlSyntaxException.class, "duplicate structure path: " + path);
      if (items.stream().anyMatch(item -> path.isBelow(item.path) == false && item.path.isBelow(path)))
        Log.fatal(HiqlSyntaxException.class, "structure paths must be declared parent before child: " + path);
      List<String> expressions = itemCtx.structureKeyExpr().stream()
          .map(ParseUtils::textOf)
          .map(String::trim)
          .toList();
      List<blater.nq.parser.script.QueryShape.ExpressionFacts> expressionFacts =
          itemCtx.structureKeyExpr().stream()
              .map(QueryShapeExtractor::expressionFacts)
              .toList();
      items.add(new StructureItem(path, expressions, expressionFacts));
    }
    return items;
  }

  /*
   * Responsibility: Carries optional select using metadata while a
   * SELECT statement is being built.
   */
  private static final class UsingInfo {
    boolean schemaOrRootPresent;
    String namespace;

    boolean hasValues() {
      return schemaOrRootPresent || namespace != null;
    }
  }

  /*
   * Responsibility: Holds one select branch while SQL text and
   * hierarchy mapping metadata are being assembled.
   */
  private static final class Branch {
    final List<SelectItem> items = new ArrayList<>();
    String sqlTail;
    blater.nq.parser.script.QueryShape queryShape;

    boolean hasHierarchyFields() {
      return items.stream().anyMatch(item -> item.outputPath != null);
    }

    boolean mapsExactPath(HierarchyPath path) {
      return items.stream().anyMatch(item -> path.equals(item.outputPath));
    }

  }

  /*
   * Responsibility: Holds one SELECT item and any hierarchy mapping
   * attached to it during statement building.
   */
  private static final class SelectItem {
    final String text;
    final String name;
    HierarchyPath outputPath;
    String appendText = null;
    boolean absentOnNull;
    blater.nq.parser.script.QueryShape.ExpressionFacts expressionFacts;

    SelectItem(String text, String name) {
      this.text = text;
      this.name = name;
      this.outputPath = null;
    }

    SelectItem(String text, String name, HierarchyPath path, boolean absentOnNull, String cmd, String cmdArg) {
      this.text = text;
      this.name = name;
      this.outputPath = path;
      this.absentOnNull = absentOnNull;
      this.appendText = parseAppendCommand(cmd, cmdArg);
    }

    private static String parseAppendCommand(String flagName, String arg) {
      if (flagName == null)
        return null;
      if (flagName.equals("append") && arg != null) {
        return switch (arg.toLowerCase()) {
          case "space" -> " ";
          case "dash" -> "-";
          case "comma" -> ",";
          case "newline" -> "\n";
          default -> null;
        };
      }
      return null;
    }

  }

  private static final class OrderItem {
    String expression;
    blater.nq.parser.script.QueryShape.ExpressionFacts expressionFacts;
    String direction;
  }

  private record StructureItem(
      HierarchyPath path,
      List<String> keyExpressions,
      List<blater.nq.parser.script.QueryShape.ExpressionFacts> expressionFacts) {
  }

}
