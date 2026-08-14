package blater.nq.parser;

import blater.nq.core.parser.HiQLLexer;
import blater.nq.parser.script.QueryShape;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Extracts top-level FROM and JOIN relation occurrences. */
final class QueryRelationSourceExtractor {
  private static final Set<String> SOURCE_TERMINATORS = Set.of(
      "where", "group", "having", "order", "limit", "offset", "fetch", "union");
  private static final Set<String> JOIN_WORDS = Set.of(
      "join", "inner", "left", "right", "full", "outer", "cross", "natural", "lateral");
  private static final Set<String> ALIAS_TERMINATORS = Set.of(
      "on", "using", "where", "group", "having", "order", "limit", "offset", "fetch", "union",
      "join", "inner", "left", "right", "full", "outer", "cross", "natural", "lateral");

  private QueryRelationSourceExtractor() {
  }

  static List<QueryShape.RelationSource> extract(List<Token> tokens) {
    int from = QueryTokenSupport.topLevelWord(tokens, "from");
    if (from < 0) {
      return List.of();
    }
    List<QueryShape.RelationSource> result = new ArrayList<>();
    ScanState state = new ScanState(from + 1);
    while (state.index < tokens.size()) {
      Token token = tokens.get(state.index);
      String word = QueryTokenSupport.word(token);
      if (state.depth == 0 && SOURCE_TERMINATORS.contains(word)) {
        break;
      }
      scanToken(tokens, result, state, token, word);
    }
    return List.copyOf(result);
  }

  private static void scanToken(
      List<Token> tokens,
      List<QueryShape.RelationSource> result,
      ScanState state,
      Token token,
      String word) {
    if (token.getType() == HiQLLexer.LPAREN) {
      scanOpeningParenthesis(tokens, result, state);
    } else if (token.getType() == HiQLLexer.RPAREN) {
      state.depth--;
      state.index++;
    } else if (state.depth == 0
        && (token.getType() == HiQLLexer.COMMA || "join".equals(word))) {
      state.expectingSource = true;
      state.index++;
    } else if (state.depth == 0 && state.expectingSource) {
      scanExpectedSource(tokens, result, state, token, word);
    } else {
      state.index++;
    }
  }

  private static void scanOpeningParenthesis(
      List<Token> tokens, List<QueryShape.RelationSource> result, ScanState state) {
    if (!state.expectingSource) {
      state.depth++;
      state.index++;
      return;
    }
    int end = QueryTokenSupport.matchingParen(tokens, state.index);
    result.add(new QueryShape.UnsupportedRelation("derived or parenthesized relation"));
    state.index = skipAlias(tokens, end < 0 ? tokens.size() : end + 1);
    state.expectingSource = false;
  }

  private static void scanExpectedSource(
      List<Token> tokens,
      List<QueryShape.RelationSource> result,
      ScanState state,
      Token token,
      String word) {
    if (JOIN_WORDS.contains(word)) {
      state.index++;
      return;
    }
    QueryTokenSupport.ParsedIdentifier parsed =
        QueryTokenSupport.identifier(tokens, state.index);
    if (parsed == null) {
      result.add(new QueryShape.UnsupportedRelation(
          "unsupported relation source near " + token.getText()));
      state.expectingSource = false;
      state.index++;
      return;
    }
    state.index = parsed.nextIndex();
    Optional<QueryShape.IdentifierPart> alias = readAlias(tokens, state);
    result.add(new QueryShape.BaseRelation(parsed.identifier(), alias, state.occurrence++));
    state.expectingSource = false;
  }

  private static Optional<QueryShape.IdentifierPart> readAlias(
      List<Token> tokens, ScanState state) {
    if (state.index < tokens.size()
        && "as".equals(QueryTokenSupport.word(tokens.get(state.index)))) {
      state.index++;
    }
    if (state.index >= tokens.size()) {
      return Optional.empty();
    }
    Token token = tokens.get(state.index);
    QueryShape.IdentifierPart alias = QueryTokenSupport.identifierPart(token);
    if (alias == null || ALIAS_TERMINATORS.contains(QueryTokenSupport.word(token))) {
      return Optional.empty();
    }
    state.index++;
    return Optional.of(alias);
  }

  private static int skipAlias(List<Token> tokens, int index) {
    ScanState state = new ScanState(index);
    readAlias(tokens, state);
    return state.index;
  }

  private static final class ScanState {
    private int index;
    private int depth;
    private int occurrence;
    private boolean expectingSource = true;

    private ScanState(int index) {
      this.index = index;
    }
  }
}
