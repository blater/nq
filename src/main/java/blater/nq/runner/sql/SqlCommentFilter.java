package blater.nq.runner.sql;

/*
 * Responsibility: Removes SQL comments and statement separators from
 * SQL text while preserving quoted string contents.
 */
public final class SqlCommentFilter {
  private SqlCommentFilter(){}

  /*
  ** Removes SQL comments and statement separators outside strings.
  */
  public static String filterOutComments(final String input) {
    final StringBuilder output = new StringBuilder(input.length());
    boolean inString = false;

    for (int pos = 0; pos < input.length(); pos++) {
      char currentChar = input.charAt(pos);

      if (currentChar == '\'' || currentChar == '"') {
        inString = !inString;
      } else if (!inString && currentChar == ';') {
        currentChar = ' ';
      }
      else if (!inString) {
        final String commentTerminator = inComment(pos, input);
        if (commentTerminator != null) {
          currentChar = ' ';
          pos = findCommentEndPos(input, pos, commentTerminator);
        }
      }
      output.append(currentChar);
    }
    return output.toString();
  }


  private static String inComment(final int pos, final String input) {
    return input.startsWith("/*", pos) ? "*/" : input.startsWith("--", pos) ? "\n" : null;
  }

  /*
  ** Finds the last character to skip for a comment.
  */
  private static int findCommentEndPos(final String sql, final int start, final String terminator) {
    int end = sql.indexOf(terminator, start + 2) -1;
    if (end  < 0) {
      end = sql.length() - 1;
      //return sql.length() - 1;
    } else if ( !"\n".equals(terminator)) {
      end = end + terminator.length();

    }
    return end;
  }
}
