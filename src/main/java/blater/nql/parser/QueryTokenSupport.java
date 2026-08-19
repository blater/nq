package blater.nql.parser;

import blater.nql.core.parser.HiQLLexer;
import blater.nql.parser.script.QueryShape;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared SQL-token navigation used by conservative query-shape extractors. */
final class QueryTokenSupport {
  private QueryTokenSupport() {
  }

  static List<Token> tokensIn(ParserRuleContext context) {
    List<Token> result = new ArrayList<>();
    if (context != null) {
      collectLeafTokens(context, result);
    }
    return result;
  }

  static String textOf(List<Token> tokens) {
    if (tokens.isEmpty()) {
      return "";
    }
    Token first = tokens.getFirst();
    Token last = tokens.getLast();
    return first.getInputStream().getText(
        new Interval(first.getStartIndex(), last.getStopIndex()));
  }

  static QueryShape.IdentifierPart identifierPart(Token token) {
    if (token == null) {
      return null;
    }
    String sql = token.getText();
    if (token.getType() == HiQLLexer.QUOTED_IDENTIFIER) {
      return new QueryShape.IdentifierPart(unquote(sql), true, sql);
    }
    if (token.getType() == HiQLLexer.IDENT
        || token.getType() >= HiQLLexer.K_AUTOCOMMIT
        && token.getType() <= HiQLLexer.K_RETURNS) {
      return new QueryShape.IdentifierPart(sql, false, sql);
    }
    return null;
  }

  static ParsedIdentifier identifier(List<Token> tokens, int start) {
    List<QueryShape.IdentifierPart> parts = new ArrayList<>();
    int index = start;
    QueryShape.IdentifierPart first = index < tokens.size()
        ? identifierPart(tokens.get(index))
        : null;
    if (first == null) {
      return null;
    }
    parts.add(first);
    index++;
    while (index + 1 < tokens.size() && tokens.get(index).getType() == HiQLLexer.DOT) {
      QueryShape.IdentifierPart next = identifierPart(tokens.get(index + 1));
      if (next == null) {
        break;
      }
      parts.add(next);
      index += 2;
    }
    return new ParsedIdentifier(new QueryShape.SqlIdentifier(parts), index);
  }

  static int topLevelWord(List<Token> tokens, String wanted) {
    int depth = 0;
    for (int index = 0; index < tokens.size(); index++) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.LPAREN) depth++;
      if (token.getType() == HiQLLexer.RPAREN) depth--;
      if (depth == 0 && wanted.equals(word(token))) return index;
    }
    return -1;
  }

  static int matchingParen(List<Token> tokens, int start) {
    int depth = 0;
    for (int index = start; index < tokens.size(); index++) {
      if (tokens.get(index).getType() == HiQLLexer.LPAREN) depth++;
      if (tokens.get(index).getType() == HiQLLexer.RPAREN && --depth == 0) return index;
    }
    return -1;
  }

  static boolean startsWithDistinct(List<Token> tokens) {
    return !tokens.isEmpty() && "distinct".equals(word(tokens.getFirst()));
  }

  static String signatureText(Token token) {
    QueryShape.IdentifierPart identifier = identifierPart(token);
    return identifier == null
        ? token.getText().toLowerCase(Locale.ROOT)
        : identifier.normalized();
  }

  static String word(Token token) {
    return token == null ? "" : token.getText().toLowerCase(Locale.ROOT);
  }

  private static String unquote(String value) {
    return value == null || value.length() < 2
        ? value
        : value.substring(1, value.length() - 1);
  }

  private static void collectLeafTokens(ParseTree node, List<Token> output) {
    if (node instanceof TerminalNode terminal) {
      Token token = terminal.getSymbol();
      if (token.getType() != Token.EOF) {
        output.add(token);
      }
      return;
    }
    for (int index = 0; index < node.getChildCount(); index++) {
      collectLeafTokens(node.getChild(index), output);
    }
  }

  record ParsedIdentifier(QueryShape.SqlIdentifier identifier, int nextIndex) {
  }
}
