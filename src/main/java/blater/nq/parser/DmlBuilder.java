package blater.nq.parser;
// IMPORTANT! DO NOT REMOVE COMMENTS, EVER!

import blater.nq.core.parser.HiQLParser;
import blater.nq.domain.Hierarchy;
import blater.nq.runner.sql.domain.ColumnDataSourceType;
import blater.nq.runner.sql.domain.ColumnDefinition;
import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.ReturnMapping;
import blater.nq.domain.SqlType;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

import static blater.nq.parser.ParseUtils.unquoteString;
import static blater.nq.parser.script.NestSqlStatementType.*;
import static blater.nq.util.Log.FATAL_SYNTAX_ERROR;
import static blater.nq.util.Log.fatal;

// Responsibility: Builds typed DML statements from ANTLR parse-tree contexts.
final class DmlBuilder {
  private DmlBuilder() { }


  /*
     Execute procedure
   */
  static NestStatement buildExecProc(HiQLParser.ExecProcContext ctx) {
    return NestStatement.proc(ctx.name().getText(), buildMappings(ctx.mappingList()));
  }

  /*
  ** Update table
  */
  static NestStatement buildDmlUpdate(HiQLParser.DmlUpdateContext ctx)
  {
    // these are the db generated fields
    //   e.g. return userId into {users.id}
    // columnName, xPath
    List<ReturnMapping> returnMappings = buildReturnMappings(ctx.returnsClause());

    // build up expressions list
    List<HiQLParser.DmlExprContext> expressions = new ArrayList<>();
    for (HiQLParser.DmlAssignmentContext assignment : ctx.dmlAssignmentList().dmlAssignment()) {
      expressions.add(assignment.dmlExpr());
    }
    if (ctx.dmlPredicateList() != null) {
      for (HiQLParser.DmlPredicateContext predicate : ctx.dmlPredicateList().dmlPredicate()) {
        expressions.add(predicate.dmlExpr());
      }
    }

    if (hasNoMappedSources(expressions)) {
      // it is an Update which doesn't reference data from the input document, just return the literal SQL
      if (!returnMappings.isEmpty())
        fatal(FATAL_SYNTAX_ERROR,"DML returns requires at least one mapped input source.");
      return NestStatement.literal(ParseUtils.textOf(ctx).trim());

    } else {
      // The update references data from the input. Return a fully mapped statement
      boolean fromTempRowset = ctx.STRING() != null;
      List<InputToColumnMap> mappings = new ArrayList<>();

      for (HiQLParser.DmlAssignmentContext assignment : ctx.dmlAssignmentList().dmlAssignment()) {
        mappings.add(buildExpressionMapping(assignment.name().getText(), assignment.dmlExpr(), false, fromTempRowset));
      }
      if (ctx.dmlPredicateList() != null) {
        for (HiQLParser.DmlPredicateContext predicate : ctx.dmlPredicateList().dmlPredicate()) {
          mappings.add(buildExpressionMapping(predicate.name().getText(), predicate.dmlExpr(), true, fromTempRowset));
        }
      }
      return NestStatement.dml(UPDATE, ctx.name().getText(), sourceRowsetName(ctx.STRING()), mappings, returnMappings);
    }
  }

  /*
     Insert into table
   */
  static NestStatement buildDmlInsert(HiQLParser.DmlInsertContext parseCtx) {
    /*
       Store the mappings of column+path for returned values into the response document
       e.g  "returns personid into {person.id}, anotherId into {another.path.id}"
     */
    List<ReturnMapping> returnMappings = buildReturnMappings(parseCtx.returnsClause());

    if (parseCtx.selectStatement() != null)  {
      //
      // It's an INSERT ... FROM SELECT.  Return the literal SQL
      //
      validateInsertSourceSelect(parseCtx.selectStatement(), returnMappings);
      return NestStatement.literal(ParseUtils.textOf(parseCtx).trim());

    } else if (hasNoMappedSources(parseCtx.dmlExprList().dmlExpr())) {
      //
      // It's an INSERT but has no mapped paths, Return the literal SQL
      //
      if (!returnMappings.isEmpty())
        fatal(FATAL_SYNTAX_ERROR, "DML returns requires at least one mapped input source.");

      return NestStatement.literal(ParseUtils.textOf(parseCtx).trim());

    } else {
      //
      // it is an insert which references paths, return a fully mapped stqtement
      //
      List<HiQLParser.NameContext> names = parseCtx.nameList() == null ? List.of() : parseCtx.nameList().name();
      List<HiQLParser.DmlExprContext> values = parseCtx.dmlExprList().dmlExpr();
      if (!names.isEmpty() && names.size() != values.size()) {
        fatal(FATAL_SYNTAX_ERROR, "INSERT column count does not match value count.");
      }

      List<InputToColumnMap> mappings = new ArrayList<>();
      boolean fromTempRowset = parseCtx.STRING() != null;
      for (int index = 0; index < values.size(); index++) {
        String sqlName = names.isEmpty() ? "$" + (index + 1) : names.get(index).getText();
        mappings.add(buildExpressionMapping(sqlName, values.get(index), false, fromTempRowset));
      }

      return NestStatement.dml(INSERT, parseCtx.name().getText(), sourceRowsetName(parseCtx.STRING()), mappings, returnMappings);
    }
  }


  private static boolean hasNoMappedSources(List<HiQLParser.DmlExprContext> expressions) {
    return expressions.stream().allMatch(expr -> findDmlSources(expr).isEmpty());
  }

  /*
   * runs syntax/semantic checks on an insert statement.
   * throws fatal error/system.exit if any found.
   */
  static void validateInsertSourceSelect(HiQLParser.SelectStatementContext ctx, List<ReturnMapping> returnMappings) {
    if (!returnMappings.isEmpty())
      fatal(FATAL_SYNTAX_ERROR,"INSERT ... SELECT does not support returns.");

    if (ctx.selectBranch().size() > 1)
      fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use hierarchy union.");

    if (ctx.structureClause() != null)
      fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use structure.");

    if (ctx.orderByClause() != null)
      for (HiQLParser.OrderItemContext item : ctx.orderByClause().orderItem()) {
        if (!item.createsNewClause().isEmpty())
          fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use createsNew.");
      }

    for (HiQLParser.SelectBranchContext branch : ctx.selectBranch()) {
      if (branch.usingClause() != null)
        fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use 'using' metadata.");

      for (HiQLParser.SelectItemContext item : branch.selectItem()) {
        if (item.mappingAlias() != null)
          fatal(FATAL_SYNTAX_ERROR, "insert select source cannot use hierarchy mapping aliases.");
      }
    }
  }

  public static Hierarchy builderReturnHierarchy(HiQLParser.ReturnsClauseContext ctx) {
  // todo
    return null ;
  }

  /*
ReturnMapping
  Responsibility: Represents one database column value to write back into the input document.
  - String columnName
  - String sourceText
 */
  private static List<ReturnMapping> buildReturnMappings(HiQLParser.ReturnsClauseContext ctx) {
    // todo - change this to build a Hierarchy instead.
    /*
     * activeObjects records the latest Node opened or created for a path.
     * example:
     *     people.person.address  -> current address
     *
     * Hierarchy...
         private final Map<HierarchyPath, Node> activePathEntries = new HashMap<>();

       Node...
         private String name = null;
         private String value = null;
         private boolean nullValue = false;
         private boolean attribute = false;
         private final List<Node> children = new ArrayList<>();

       HierarchyPath
         private final List<String> pathParts;   // fromDottedPath...

* public class ReturnMapping {
  private final String columnName;
  private final String xpath;
}
     */
    return ctx == null ? List.of()
        : ctx.returnMapping().stream()
        .map(parserMapping -> new ReturnMapping(parserMapping.name().getText(), PathContextMapper.toSlashPath(parserMapping.path())))
        .toList();
  }

  static NestStatement buildDmlDelete(HiQLParser.DmlDeleteContext ctx) {
    List<HiQLParser.DmlExprContext> expressions = ctx.dmlPredicateList() == null
        ? List.of()
        : ctx.dmlPredicateList().dmlPredicate().stream()
        .map(HiQLParser.DmlPredicateContext::dmlExpr)
        .toList();
    if (hasNoMappedSources(expressions)) {
      return NestStatement.literal(ParseUtils.textOf(ctx).trim());
    }

    List<InputToColumnMap> mappings = new ArrayList<>();
    boolean fromTempRowset = ctx.STRING() != null;
    if (ctx.dmlPredicateList() != null) {
      for (HiQLParser.DmlPredicateContext predicate : ctx.dmlPredicateList().dmlPredicate()) {
        mappings.add(buildExpressionMapping(predicate.name().getText(), predicate.dmlExpr(), true, fromTempRowset));
      }
    }
    return NestStatement.dml(
        DELETE, ctx.name().getText(),
        sourceRowsetName(ctx.STRING()),
        mappings,
        List.of());
  }

  private static List<InputToColumnMap> buildMappings(HiQLParser.MappingListContext parseCtx) {
    List<InputToColumnMap> result = new ArrayList<>();
    for (HiQLParser.MappingContext mapping : parseCtx.mapping()) {
      result.add(toColumnMap(mapping));
    }
    return result;
  }

  private static InputToColumnMap toColumnMap(HiQLParser.MappingContext parseCtx) {
    String sqlName = parseCtx.name().getText();

    String sourceExpression = null;
    boolean isLiteral = false;

    // TODO why is this a loop?
    //     why do we calculate and then throw away sourceExpression & isLiteral for every element?
    //     can mappingItem actually contain multiple entries? if so what are they, are we missing things
    //     by throwing away the calculations?
    for (HiQLParser.MappingItemContext item : parseCtx.mappingItem()) {
      if (item.STRING() != null) {
        sourceExpression = unquoteString(item.STRING().getText());
        isLiteral = true;

      } else if (item.path() != null) {
        sourceExpression = PathContextMapper.toSlashPath(item.path());

      } else {
        String operationFlag = item.name().getText().toLowerCase();
        switch (operationFlag) {
          case "literal" -> {
            sourceExpression = unquoteString(item.optVal().STRING().getText());
            isLiteral = true;
          }
          case "xpathmapping" -> {
            sourceExpression = unquoteString(item.optVal().STRING().getText());
          }
          default -> sourceExpression = fatal(FATAL_SYNTAX_ERROR, "Unknown mapping option: " + operationFlag);
        }
      }
    }

    if (sourceExpression == null)
      fatal(FATAL_SYNTAX_ERROR, "No source expression in mapping for column: " + sqlName);

    return InputToColumnMap.newInstance(sqlName, sourceExpression, isLiteral);
  }



/*
HierarchyPath
   Responsibility: Represents a dot-separated target path inside a
  + private List<String> hiqlPathParts = null;
  + private boolean attribute = false;
*/

  private static String sourceRowsetName(org.antlr.v4.runtime.tree.TerminalNode token) {
    return token == null ? null : unquoteString(token.getText());
  }


  private static InputToColumnMap buildExpressionMapping(String sqlName, HiQLParser.DmlExprContext expr, boolean key, boolean fromTempRowset)
  {
    List<HiQLParser.DmlSourceContext> sources = findDmlSources(expr);
    String text = ParseUtils.textOf(expr).trim();
    if (sources.size() > 1) {
      fatal(FATAL_SYNTAX_ERROR, "DML expressions currently support one mapped source per column: " + text);
    }
    if (sources.isEmpty()) {
      return literalExpressionMapping(sqlName, text, key);
    }

    HiQLParser.DmlSourceContext source = sources.getFirst();
    String sourceText = ParseUtils.textOf(source);
    String sourceExpression = fromTempRowset
        ? PathContextMapper.toDottedPath(source.path())
        : PathContextMapper.toSlashPath(source.path());
    String sqlFunction = text.equals(sourceText)
        ? ""
        : text.replace(sourceText, "${0}");

    return new InputToColumnMap(
        new ColumnDefinition(sqlName, SqlType.STRING, sqlFunction, key, key ? 1 : -99, ColumnDataSourceType.NORMAL),
        sourceExpression,
        null, false);
  }

  private static InputToColumnMap literalExpressionMapping(String sqlName, String expression, boolean key) {
    String sourceExpression;
    if (expression.startsWith("'") && expression.endsWith("'")) {
      sourceExpression = unquoteString(expression);
    } else if (expression.matches("[+-]?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        || expression.startsWith("${")) {
      sourceExpression = expression;
    } else {
      sourceExpression = fatal(
          FATAL_SYNTAX_ERROR,
          "DML expression must contain one mapped source or a literal value: " + expression);
    }
    return new InputToColumnMap(
        new ColumnDefinition(sqlName, SqlType.STRING, "", key, key ? 1 : -99, ColumnDataSourceType.NORMAL),
        sourceExpression, null, true);
  }




  private static List<HiQLParser.DmlSourceContext> findDmlSources(ParseTree tree) {
    List<HiQLParser.DmlSourceContext> sources = new ArrayList<>();
    collectDmlSources(tree, sources);
    return sources;
  }

  private static void collectDmlSources(ParseTree tree, List<HiQLParser.DmlSourceContext> sources) {
    if (tree instanceof HiQLParser.DmlSourceContext source) {
      if (!isTemplatePlaceholder(source)) {
        sources.add(source);
      }
      return;
    }
    for (int index = 0; index < tree.getChildCount(); index++) {
      collectDmlSources(tree.getChild(index), sources);
    }
  }

  private static boolean isTemplatePlaceholder(HiQLParser.DmlSourceContext source) {
    int start = source.start.getStartIndex();
    if (start <= 0) {
      return false;
    }
    return source.start.getInputStream().getText(
        new org.antlr.v4.runtime.misc.Interval(start - 1, start - 1)).equals("$");
  }

}
