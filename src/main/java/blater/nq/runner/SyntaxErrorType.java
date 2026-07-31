package blater.nq.runner;

/*
 * Responsibility: Names row-construction problems detected before a
 * SQL statement is executed.
 */
public enum SyntaxErrorType {
  OK,
  EMPTY_INSERT,
  UPDATE_MISSING_KEY,
  UPDATE_NO_VALUES,
  DELETE_MISSING_KEY,
  DELETE_UID_KEY,
  AMBIGUOUS_ROW_CONTEXT,
  DUPLICATE_TARGET_COLUMN_ASSIGNMENT,
  UNRESOLVABLE_MULTI_VALUE,
  UNSUPPORTED_SOURCE_PATH
}
