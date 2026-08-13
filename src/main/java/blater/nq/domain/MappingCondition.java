package blater.nq.domain;

/*
 * Responsibility: Holds one condition that decides whether a hierarchy
 * field or node-opening rule applies to the current query row.
 */
public record MappingCondition(
    String fieldName,
    Operator operator,
    Object expected,
    SqlType sqlType) {
  public MappingCondition {
    if (sqlType == null) {
      sqlType = SqlType.STRING;
    }
  }

  public static MappingCondition eq(String fieldName, String expectedValue) {
    return new MappingCondition(fieldName, Operator.EQ, expectedValue, SqlType.STRING);
  }
}
