package blater.nql.parser;

import blater.nql.core.parser.HiQLLexer;
import blater.nql.parser.script.QueryShape;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Extracts aggregate, direct-column, and structural facts from one SQL expression. */
final class QueryExpressionFactsExtractor {
  private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

  private QueryExpressionFactsExtractor() {
  }

  static QueryShape.ExpressionFacts extract(ParserRuleContext context) {
    List<Token> original = QueryTokenSupport.tokensIn(context);
    List<Token> semantic = new ArrayList<>(original);
    if (QueryTokenSupport.startsWithDistinct(semantic)) {
      semantic.removeFirst();
    }
    return extract(semantic, QueryTokenSupport.textOf(original));
  }

  static QueryShape.ExpressionFacts extract(List<Token> tokens, String originalSql) {
    List<QueryShape.TokenSignature> signature = tokens.stream()
        .map(token -> new QueryShape.TokenSignature(
            token.getType(), QueryTokenSupport.signatureText(token)))
        .toList();
    return new QueryShape.ExpressionFacts(
        originalSql, signature, directColumn(tokens), aggregate(tokens));
  }

  static QueryShape.TruthValue aggregate(List<Token> tokens) {
    boolean unknownCall = false;
    for (int index = 0; index + 1 < tokens.size(); index++) {
      QueryShape.IdentifierPart name = QueryTokenSupport.identifierPart(tokens.get(index));
      if (name == null || tokens.get(index + 1).getType() != HiQLLexer.LPAREN) {
        continue;
      }
      if (AGGREGATES.contains(name.text().toLowerCase(Locale.ROOT))) {
        return QueryShape.TruthValue.YES;
      }
      unknownCall = true;
    }
    return unknownCall ? QueryShape.TruthValue.UNKNOWN : QueryShape.TruthValue.NO;
  }

  private static Optional<QueryShape.DirectColumnReference> directColumn(List<Token> tokens) {
    if (tokens.isEmpty()) {
      return Optional.empty();
    }
    List<QueryShape.IdentifierPart> parts = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      if (index % 2 == 0) {
        QueryShape.IdentifierPart part = QueryTokenSupport.identifierPart(tokens.get(index));
        if (part == null) return Optional.empty();
        parts.add(part);
      } else if (tokens.get(index).getType() != HiQLLexer.DOT) {
        return Optional.empty();
      }
    }
    if (parts.size() * 2 - 1 != tokens.size()) {
      return Optional.empty();
    }
    QueryShape.IdentifierPart column = parts.getLast();
    Optional<QueryShape.SqlIdentifier> qualifier = parts.size() == 1
        ? Optional.empty()
        : Optional.of(new QueryShape.SqlIdentifier(parts.subList(0, parts.size() - 1)));
    return Optional.of(new QueryShape.DirectColumnReference(qualifier, column));
  }
}
