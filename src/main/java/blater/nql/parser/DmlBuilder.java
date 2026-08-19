package blater.nql.parser;
// IMPORTANT! DO NOT REMOVE COMMENTS, EVER!

import blater.nql.core.parser.HiQLParser;
import blater.nql.domain.Hierarchy;
import blater.nql.runner.sql.domain.ColumnDataSourceType;
import blater.nql.runner.sql.domain.ColumnDefinition;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.parser.script.NestStatement;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.domain.SqlType;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

import static blater.nql.parser.ParseUtils.unquoteString;
import static blater.nql.parser.script.NestSqlStatementType.*;
import static blater.nql.util.Log.FATAL_SYNTAX_ERROR;
import static blater.nql.util.Log.fatal;

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
    return DmlMutationBuilder.buildUpdate(ctx);
  }

  /*
     Insert into table
   */
  static NestStatement buildDmlInsert(HiQLParser.DmlInsertContext parseCtx) {
    return DmlMutationBuilder.buildInsert(parseCtx);
  }


  static boolean hasNoMappedSources(List<HiQLParser.DmlExprContext> expressions) {
    return expressions.stream().allMatch(expr -> findDmlSources(expr).isEmpty());
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
  static List<ReturnMapping> buildReturnMappings(HiQLParser.ReturnsClauseContext ctx) {
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

  static String sourceRowsetName(org.antlr.v4.runtime.tree.TerminalNode token) {
    return token == null ? null : unquoteString(token.getText());
  }


  static InputToColumnMap buildExpressionMapping(String sqlName, HiQLParser.DmlExprContext expr, boolean key, boolean fromTempRowset)
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
