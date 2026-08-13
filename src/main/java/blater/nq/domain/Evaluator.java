package blater.nq.domain;

import blater.nq.runner.sql.domain.QueryResultRow;

/*
 * Responsibility: Evaluates one parsed hierarchy-mapping condition
 * against the current query result row.
 */
public final class Evaluator {
  public static boolean evaluate(MappingCondition condition, QueryResultRow queryResultRow) {
    Object left = queryResultRow.getValue(condition.fieldName());
    Object expected = condition.expected();
    if (left == null || expected == null) {
      return false;
    }

    return compare(condition.sqlType(), left, expected) == 0;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static int compare(SqlType type, Object left, Object expected) {
    left = type.cast(left);
    expected = type.cast(expected);
    return switch (type) {
      case STRING -> ((String) left).compareTo((String) expected);
      case INTEGER -> ((Integer) left).compareTo((Integer) expected);
      case LONG -> ((Long) left).compareTo((Long) expected);
      case SHORT -> ((Short) left).compareTo((Short) expected);
      case FLOAT -> ((Float) left).compareTo((Float) expected);
      case DOUBLE -> ((Double) left).compareTo((Double) expected);
      case NUMBER, DATE -> ((Comparable) left).compareTo(expected);
    };
  }
}
