package blater.nql.parser;

import blater.nql.core.parser.HiQLParser;
import blater.nql.parser.script.QueryShape;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.List;

/** Conservatively coordinates extraction of top-level SQL facts required by key inference. */
final class QueryShapeExtractor {
  private QueryShapeExtractor() {
  }

  static QueryShape extract(HiQLParser.SelectBranchContext branch) {
    List<QueryShape.ExpressionFacts> expressions = branch.selectItem().stream()
        .map(HiQLParser.SelectItemContext::selectExpr)
        .map(QueryShapeExtractor::expressionFacts)
        .toList();
    boolean distinct = !branch.selectItem().isEmpty()
        && QueryTokenSupport.startsWithDistinct(
            QueryTokenSupport.tokensIn(branch.selectItem(0).selectExpr()));
    List<Token> tail = QueryTokenSupport.tokensIn(branch.sqlTail());
    QueryShape.TruthValue aggregate = combinedAggregate(expressions, tail);
    return new QueryShape(
        QueryRelationSourceExtractor.extract(tail),
        QueryGroupingExtractor.extract(tail),
        new QueryShape.QueryCharacteristics(
            distinct ? QueryShape.TruthValue.YES : QueryShape.TruthValue.NO,
            aggregate));
  }

  static QueryShape.ExpressionFacts expressionFacts(ParserRuleContext context) {
    return QueryExpressionFactsExtractor.extract(context);
  }

  private static QueryShape.TruthValue combinedAggregate(
      List<QueryShape.ExpressionFacts> expressions, List<Token> tail) {
    QueryShape.TruthValue aggregate = QueryShape.combineAggregate(expressions);
    QueryShape.TruthValue tailAggregate = QueryExpressionFactsExtractor.aggregate(tail);
    if (tailAggregate == QueryShape.TruthValue.YES) {
      return QueryShape.TruthValue.YES;
    }
    return aggregate == QueryShape.TruthValue.NO
        && tailAggregate == QueryShape.TruthValue.UNKNOWN
        ? QueryShape.TruthValue.UNKNOWN
        : aggregate;
  }
}
