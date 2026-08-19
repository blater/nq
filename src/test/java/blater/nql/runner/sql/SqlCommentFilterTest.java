package blater.nql.runner.sql;

import blater.nql.runner.sql.SqlCommentFilter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlCommentFilterTest {
  @Test
  void removesBlockComments() {
    assertEquals(
        "select 1 select 2",
        normalized(SqlCommentFilter.filterOutComments("select 1 /* skip */ select 2;")));
  }

  @Test
  void keepsCommentMarkersAndSemicolonsInsideStrings() {
    assertEquals(
        "select '/* not a comment */; still text' ",
        SqlCommentFilter.filterOutComments("select '/* not a comment */; still text';"));
  }

  @Test
  void handlesEscapedSingleQuotesLikeTheOriginalScanner() {
    assertEquals(
        "select 'it''s; ok' ",
        SqlCommentFilter.filterOutComments("select 'it''s; ok';"));
  }

  @Test
  void removesUnterminatedBlockComment() {
    assertEquals(
        "select 1",
        normalized(SqlCommentFilter.filterOutComments("select 1 /* unfinished")));
  }

  @Test
  void stripsLineCommentUntilNewline() {
    String sql = "select 1 -- skip me\nselect 2;";

    assertEquals("select 1 select 2", normalized(SqlCommentFilter.filterOutComments(sql)));
  }

  @Test
  void stripsLineCommentToEndOfInput() {
    assertEquals(
        "select 1",
        normalized(SqlCommentFilter.filterOutComments("select 1 -- trailing")));
  }

  @Test
  void keepsLineCommentMarkerInsideString() {
    assertEquals(
        "select '-- not a comment' ",
        SqlCommentFilter.filterOutComments("select '-- not a comment';"));
  }

  @Test
  void handlesMultipleBlockComments() {
    assertEquals(
        "a b c",
        normalized(SqlCommentFilter.filterOutComments("a/**/b/**/c")));
  }

  @Test
  void replacesSemicolonBetweenStatementsWithSpace() {
    assertEquals(
        "select 1 select 2",
        SqlCommentFilter.filterOutComments("select 1;select 2"));
  }

  @Test
  void handlesCommentAdjacentToString() {
    assertEquals(
        "'a' 'b'",
        SqlCommentFilter.filterOutComments("'a'/* x */'b'"));
  }

  @Test
  void leavesDivisionAndMultiplicationAlone() {
    assertEquals(
        "select a/b * c",
        SqlCommentFilter.filterOutComments("select a/b * c"));
  }

  @Test
  void leavesSingleDashAlone() {
    assertEquals(
        "select a - b",
        SqlCommentFilter.filterOutComments("select a - b"));
  }

  @Test
  void handlesEmptyInput() {
    assertEquals("", SqlCommentFilter.filterOutComments(""));
  }

  @Test
  void ignoresQuoteInsideBlockComment() {
    String sql = "a /* it's fine */ b ';' c";

    assertEquals("a b ';' c", normalized(SqlCommentFilter.filterOutComments(sql)));
  }

  private static String normalized(String sql) {
    return sql.trim().replaceAll("\\s+", " ");
  }
}
