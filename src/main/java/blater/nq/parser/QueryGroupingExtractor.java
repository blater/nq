package blater.nq.parser;

import blater.nq.core.parser.HiQLLexer;
import blater.nq.parser.script.QueryShape;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Extracts conservative GROUP BY expression facts. */
final class QueryGroupingExtractor {
  private static final Set<String> TERMINATORS = Set.of(
      "having", "limit", "offset", "fetch", "union");
  private static final Set<String> ADVANCED = Set.of("rollup", "cube", "grouping", "sets");

  private QueryGroupingExtractor() {
  }

  static QueryShape.Grouping extract(List<Token> tokens) {
    int group = QueryTokenSupport.topLevelWord(tokens, "group");
    if (group < 0) {
      return new QueryShape.NoGrouping();
    }
    if (group + 1 >= tokens.size()
        || !"by".equals(QueryTokenSupport.word(tokens.get(group + 1)))) {
      return new QueryShape.UnsupportedGrouping("GROUP is not followed by BY");
    }
    List<Token> groupingTokens = tokens.subList(group + 2, groupingEnd(tokens, group + 2));
    if (groupingTokens.stream().map(QueryTokenSupport::word).anyMatch(ADVANCED::contains)) {
      return new QueryShape.UnsupportedGrouping("advanced grouping construct");
    }
    List<List<Token>> groups = splitTopLevel(groupingTokens);
    if (groups.isEmpty() || groups.stream().anyMatch(List::isEmpty)) {
      return new QueryShape.UnsupportedGrouping("GROUP BY contains no usable expressions");
    }
    return new QueryShape.KnownGrouping(groups.stream()
        .map(groupTokens -> QueryExpressionFactsExtractor.extract(
            groupTokens, QueryTokenSupport.textOf(groupTokens)))
        .toList());
  }

  private static int groupingEnd(List<Token> tokens, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.LPAREN) depth++;
      if (token.getType() == HiQLLexer.RPAREN) depth--;
      if (depth == 0 && TERMINATORS.contains(QueryTokenSupport.word(token))) {
        return index;
      }
    }
    return tokens.size();
  }

  private static List<List<Token>> splitTopLevel(List<Token> tokens) {
    List<List<Token>> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int index = 0; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.LPAREN) depth++;
      if (token.getType() == HiQLLexer.RPAREN) depth--;
      if (depth == 0 && token.getType() == HiQLLexer.COMMA) {
        result.add(List.copyOf(tokens.subList(start, index)));
        start = index + 1;
      }
    }
    if (start < tokens.size()) {
      result.add(List.copyOf(tokens.subList(start, tokens.size())));
    }
    return result;
  }
}
