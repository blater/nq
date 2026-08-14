package blater.nq.parser;

import blater.nq.core.parser.HiQLLexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

/** Extracts source-preserving token text and identifiers from SELECT expressions. */
final class SelectTokenReader {
  private SelectTokenReader() {
  }

  static List<Token> tokensIn(ParserRuleContext context) {
    List<Token> result = new ArrayList<>();
    if (context != null) {
      collectLeafTokens(context, result);
    }
    return result;
  }

  static String joinText(List<Token> tokens) {
    if (tokens.isEmpty()) {
      return "";
    }
    Token first = tokens.getFirst();
    Token last = tokens.getLast();
    return first.getInputStream().getText(
        new Interval(first.getStartIndex(), last.getStopIndex()));
  }

  static String trailingIdentifier(List<Token> tokens) {
    for (int index = tokens.size() - 1; index >= 0; index--) {
      Token token = tokens.get(index);
      if (token.getType() == HiQLLexer.IDENT
          || token.getType() == HiQLLexer.QUOTED_IDENTIFIER) {
        return ParseUtils.unquoteIdentifier(token.getText());
      }
    }
    return null;
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
}
