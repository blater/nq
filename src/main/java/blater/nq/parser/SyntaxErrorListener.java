package blater.nq.parser;

import blater.nq.core.parser.HiQLParser;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.Vocabulary;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import static blater.nq.util.Log.FATAL_SYNTAX_ERROR;
import static blater.nq.util.Log.fatal;

/*
 * Responsibility: Routes ANTLR syntax callbacks through the project
 * logging and fatal-error policy.
 */
final class SyntaxErrorListener extends BaseErrorListener {
  @Override
  public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                          int line, int column, String antlrMessage, RecognitionException cause) {
    String message = recognizer instanceof Parser parser
        ? parserMessage(parser, offendingSymbol, antlrMessage)
        : antlrMessage;
    fatal(FATAL_SYNTAX_ERROR, "line " + line + ":" + column + " " + message);
  }

  private static String parserMessage(Parser parser, Object offendingSymbol, String fallback) {
    String expected = expectedTokens(parser);
    if (expected == null) {
      return fallback;
    }

    if (!(offendingSymbol instanceof Token token)) {
      return "expected " + expected;
    }
    if (token.getType() == Token.EOF) {
      return "expected " + expected + " at line end";
    }
    if (fallback.startsWith("missing ")) {
      return "expected " + expected + " before " + offendingToken(token);
    }
    return "unexpected " + offendingToken(token) + "; expected " + expected;
  }

  private static String expectedTokens(Parser parser) {
    Vocabulary vocabulary = parser.getVocabulary();
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (int tokenType : parser.getExpectedTokens().toList()) {
      names.add(tokenName(vocabulary, tokenType));
    }
    return joinAlternatives(List.copyOf(names));
  }

  private static String tokenName(Vocabulary vocabulary, int tokenType) {
    return switch (tokenType) {
      case Token.EOF -> "the end of input";
      case HiQLParser.TERM -> "'\\g'";
      case HiQLParser.IDENT -> "an identifier";
      case HiQLParser.QUOTED_IDENTIFIER -> "a quoted identifier";
      case HiQLParser.STRING -> "a quoted string";
      case HiQLParser.INTEGER -> "a number";
      default -> vocabularyName(vocabulary, tokenType);
    };
  }

  private static String vocabularyName(Vocabulary vocabulary, int tokenType) {
    String literal = vocabulary.getLiteralName(tokenType);
    if (literal != null) {
      return literal;
    }

    String symbolic = vocabulary.getSymbolicName(tokenType);
    if (symbolic == null) {
      return "token " + tokenType;
    }
    if (symbolic.startsWith("K_")) {
      return "'" + symbolic.substring(2).toLowerCase(Locale.ROOT) + "'";
    }
    return symbolic.toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String joinAlternatives(List<String> names) {
    return switch (names.size()) {
      case 0 -> null;
      case 1 -> names.getFirst();
      case 2 -> names.getFirst() + " or " + names.getLast();
      default -> String.join(", ", names.subList(0, names.size() - 1))
          + ", or " + names.getLast();
    };
  }

  private static String offendingToken(Token token) {
    String text = token.getText();
    if (text == null) {
      return "token " + token.getType();
    }
    return "'" + text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t") + "'";
  }

}
