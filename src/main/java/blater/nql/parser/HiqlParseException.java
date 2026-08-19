package blater.nql.parser;

/*
 * Responsibility: Tql parser exceptions - facade for antlr exceptions
 */
public class HiqlParseException extends Exception {
  public HiqlParseException(String message) {
    super(message);
  }
}
