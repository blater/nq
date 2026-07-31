package blater.nq.parser;

import blater.nq.core.parser.HiQLLexer;
import blater.nq.core.parser.HiQLParser;
import blater.nq.parser.script.QueryShape;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Conservatively extracts the top-level SQL facts required by key inference. */
final class QueryShapeExtractor {
  private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");
  private static final Set<String> SOURCE_TERMINATORS = Set.of(
      "where", "group", "having", "order", "limit", "offset", "fetch", "union");
  private static final Set<String> JOIN_WORDS = Set.of(
      "join", "inner", "left", "right", "full", "outer", "cross", "natural", "lateral");
  private static final Set<String> ALIAS_TERMINATORS = Set.of(
      "on", "using", "where", "group", "having", "order", "limit", "offset", "fetch", "union",
      "join", "inner", "left", "right", "full", "outer", "cross", "natural", "lateral");

  private QueryShapeExtractor() {
  }

  static QueryShape extract(HiQLParser.SelectBranchContext branch) {
    List<QueryShape.ExpressionFacts> expressions = branch.selectItem().stream()
        .map(HiQLParser.SelectItemContext::selectExpr)
        .map(QueryShapeExtractor::expressionFacts)
        .toList();
    boolean distinct = !branch.selectItem().isEmpty()
        && startsWithDistinct(tokensIn(branch.selectItem(0).selectExpr()));
    List<Token> tail = tokensIn(branch.sqlTail());
    QueryShape.TruthValue aggregate = QueryShape.combineAggregate(expressions);
    QueryShape.TruthValue tailAggregate = aggregate(tail);
    if (tailAggregate == QueryShape.TruthValue.YES) aggregate = QueryShape.TruthValue.YES;
    if (aggregate == QueryShape.TruthValue.NO && tailAggregate == QueryShape.TruthValue.UNKNOWN) {
      aggregate = QueryShape.TruthValue.UNKNOWN;
    }
    return new QueryShape(
        relationSources(tail),
        grouping(tail),
        new QueryShape.QueryCharacteristics(
            distinct ? QueryShape.TruthValue.YES : QueryShape.TruthValue.NO,
            aggregate));
  }

  static QueryShape.ExpressionFacts expressionFacts(ParserRuleContext context) {
    List<Token> original = tokensIn(context);
    List<Token> semantic = new ArrayList<>(original);
    if (startsWithDistinct(semantic)) semantic.removeFirst();
    return expressionFacts(semantic, textOf(original));
  }

  private static QueryShape.ExpressionFacts expressionFacts(List<Token> tokens, String originalSql) {
    QueryShape.TruthValue aggregate = aggregate(tokens);
    Optional<QueryShape.DirectColumnReference> column = directColumn(tokens);
    List<QueryShape.TokenSignature> signature = tokens.stream()
        .map(token -> new QueryShape.TokenSignature(
            token.getType(), signatureText(token)))
        .toList();
    return new QueryShape.ExpressionFacts(originalSql, signature, column, aggregate);
  }

  private static List<QueryShape.RelationSource> relationSources(List<Token> tokens) {
    int from = topLevelWord(tokens, "from", 0);
    if (from < 0) return List.of();
    List<QueryShape.RelationSource> result = new ArrayList<>();
    int depth = 0;
    boolean expectingSource = true;
    int occurrence = 0;
    for (int index = from + 1; index < tokens.size();) {
      Token token = tokens.get(index);
      String word = word(token);
      if (depth == 0 && SOURCE_TERMINATORS.contains(word)) break;
      if (token.getType() == HiQLLexer.LPAREN) {
        if (expectingSource) {
          int end = matchingParen(tokens, index);
          result.add(new QueryShape.UnsupportedRelation("derived or parenthesized relation"));
          index = end < 0 ? tokens.size() : end + 1;
          index = skipAlias(tokens, index);
          expectingSource = false;
          continue;
        }
        depth++;
        index++;
        continue;
      }
      if (token.getType() == HiQLLexer.RPAREN) {
        depth--;
        index++;
        continue;
      }
      if (depth == 0 && (token.getType() == HiQLLexer.COMMA || "join".equals(word))) {
        expectingSource = true;
        index++;
        continue;
      }
      if (depth == 0 && expectingSource) {
        if (JOIN_WORDS.contains(word)) {
          index++;
          continue;
        }
        ParsedIdentifier parsed = identifier(tokens, index);
        if (parsed == null) {
          result.add(new QueryShape.UnsupportedRelation("unsupported relation source near " + token.getText()));
          expectingSource = false;
          index++;
          continue;
        }
        index = parsed.nextIndex();
        Optional<QueryShape.IdentifierPart> alias = Optional.empty();
        if (index < tokens.size() && "as".equals(word(tokens.get(index)))) index++;
        if (index < tokens.size() && identifierPart(tokens.get(index)) != null
            && !ALIAS_TERMINATORS.contains(word(tokens.get(index)))) {
          alias = Optional.of(identifierPart(tokens.get(index)));
          index++;
        }
        result.add(new QueryShape.BaseRelation(parsed.identifier(), alias, occurrence++));
        expectingSource = false;
        continue;
      }
      index++;
    }
    return List.copyOf(result);
  }

  private static QueryShape.Grouping grouping(List<Token> tokens) {
    int group = topLevelWord(tokens, "group", 0);
    if (group < 0) return new QueryShape.NoGrouping();
    if (group + 1 >= tokens.size() || !"by".equals(word(tokens.get(group + 1)))) {
      return new QueryShape.UnsupportedGrouping("GROUP is not followed by BY");
    }
    int end = tokens.size();
    int depth = 0;
    for (int index = group + 2; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.LPAREN) depth++;
      if (token.getType() == HiQLLexer.RPAREN) depth--;
      if (depth == 0 && Set.of("having", "limit", "offset", "fetch", "union")
          .contains(word(token))) {
        end = index;
        break;
      }
    }
    List<Token> groupingTokens = tokens.subList(group + 2, end);
    if (groupingTokens.stream().map(QueryShapeExtractor::word)
        .anyMatch(Set.of("rollup", "cube", "grouping", "sets")::contains)) {
      return new QueryShape.UnsupportedGrouping("advanced grouping construct");
    }
    List<List<Token>> groups = splitTopLevel(groupingTokens);
    if (groups.isEmpty() || groups.stream().anyMatch(List::isEmpty)) {
      return new QueryShape.UnsupportedGrouping("GROUP BY contains no usable expressions");
    }
    return new QueryShape.KnownGrouping(groups.stream()
        .map(groupTokens -> expressionFacts(groupTokens, textOf(groupTokens)))
        .toList());
  }

  private static Optional<QueryShape.DirectColumnReference> directColumn(List<Token> tokens) {
    if (tokens.isEmpty()) return Optional.empty();
    List<QueryShape.IdentifierPart> parts = new ArrayList<>();
    for (int index = 0; index < tokens.size(); index++) {
      if (index % 2 == 0) {
        QueryShape.IdentifierPart part = identifierPart(tokens.get(index));
        if (part == null) return Optional.empty();
        parts.add(part);
      } else if (tokens.get(index).getType() != HiQLLexer.DOT) {
        return Optional.empty();
      }
    }
    if (parts.size() * 2 - 1 != tokens.size()) return Optional.empty();
    QueryShape.IdentifierPart column = parts.getLast();
    Optional<QueryShape.SqlIdentifier> qualifier = parts.size() == 1
        ? Optional.empty()
        : Optional.of(new QueryShape.SqlIdentifier(parts.subList(0, parts.size() - 1)));
    return Optional.of(new QueryShape.DirectColumnReference(qualifier, column));
  }

  private static QueryShape.TruthValue aggregate(List<Token> tokens) {
    boolean unknownCall = false;
    for (int index = 0; index + 1 < tokens.size(); index++) {
      QueryShape.IdentifierPart name = identifierPart(tokens.get(index));
      if (name == null || tokens.get(index + 1).getType() != HiQLLexer.LPAREN) continue;
      if (AGGREGATES.contains(name.text().toLowerCase(Locale.ROOT))) {
        return QueryShape.TruthValue.YES;
      }
      unknownCall = true;
    }
    return unknownCall ? QueryShape.TruthValue.UNKNOWN : QueryShape.TruthValue.NO;
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
    if (start < tokens.size()) result.add(List.copyOf(tokens.subList(start, tokens.size())));
    return result;
  }

  private static ParsedIdentifier identifier(List<Token> tokens, int start) {
    List<QueryShape.IdentifierPart> parts = new ArrayList<>();
    int index = start;
    QueryShape.IdentifierPart first = index < tokens.size() ? identifierPart(tokens.get(index)) : null;
    if (first == null) return null;
    parts.add(first);
    index++;
    while (index + 1 < tokens.size() && tokens.get(index).getType() == HiQLLexer.DOT) {
      QueryShape.IdentifierPart next = identifierPart(tokens.get(index + 1));
      if (next == null) break;
      parts.add(next);
      index += 2;
    }
    return new ParsedIdentifier(new QueryShape.SqlIdentifier(parts), index);
  }

  private static QueryShape.IdentifierPart identifierPart(Token token) {
    if (token == null) return null;
    String sql = token.getText();
    if (token.getType() == HiQLLexer.QUOTED_IDENTIFIER) {
      return new QueryShape.IdentifierPart(unquote(sql), true, sql);
    }
    if (token.getType() == HiQLLexer.IDENT || token.getType() >= HiQLLexer.K_AUTOCOMMIT
        && token.getType() <= HiQLLexer.K_RETURNS) {
      return new QueryShape.IdentifierPart(sql, false, sql);
    }
    return null;
  }

  private static int topLevelWord(List<Token> tokens, String wanted, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.LPAREN) depth++;
      if (token.getType() == HiQLLexer.RPAREN) depth--;
      if (depth == 0 && wanted.equals(word(token))) return index;
    }
    return -1;
  }

  private static int matchingParen(List<Token> tokens, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      if (tokens.get(index).getType() == HiQLLexer.LPAREN) depth++;
      if (tokens.get(index).getType() == HiQLLexer.RPAREN && --depth == 0) return index;
    }
    return -1;
  }

  private static int skipAlias(List<Token> tokens, int index) {
    if (index < tokens.size() && "as".equals(word(tokens.get(index)))) index++;
    if (index < tokens.size() && identifierPart(tokens.get(index)) != null
        && !ALIAS_TERMINATORS.contains(word(tokens.get(index)))) index++;
    return index;
  }

  private static boolean startsWithDistinct(List<Token> tokens) {
    return !tokens.isEmpty() && "distinct".equals(word(tokens.getFirst()));
  }

  private static String signatureText(Token token) {
    QueryShape.IdentifierPart identifier = identifierPart(token);
    if (identifier != null) return identifier.normalized();
    return token.getText().toLowerCase(Locale.ROOT);
  }

  private static String word(Token token) {
    return token == null ? "" : token.getText().toLowerCase(Locale.ROOT);
  }

  private static String unquote(String value) {
    if (value == null || value.length() < 2) return value;
    return value.substring(1, value.length() - 1);
  }

  private static List<Token> tokensIn(ParserRuleContext context) {
    List<Token> result = new ArrayList<>();
    if (context != null) collectLeafTokens(context, result);
    return result;
  }

  private static void collectLeafTokens(ParseTree node, List<Token> out) {
    if (node instanceof TerminalNode terminal) {
      Token token = terminal.getSymbol();
      if (token.getType() != Token.EOF) out.add(token);
      return;
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      collectLeafTokens(node.getChild(index), out);
    }
  }

  private static String textOf(List<Token> tokens) {
    if (tokens.isEmpty()) return "";
    Token first = tokens.getFirst();
    Token last = tokens.getLast();
    return first.getInputStream().getText(new Interval(first.getStartIndex(), last.getStopIndex()));
  }

  private record ParsedIdentifier(QueryShape.SqlIdentifier identifier, int nextIndex) {
  }
}
