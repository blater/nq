package blater.nq.domain;

/** Format-neutral scalar semantics retained while values cross hierarchy and SQL boundaries. */
public enum ScalarKind {
  STRING,
  NUMBER,
  BOOLEAN;

  public static ScalarKind merge(ScalarKind left, ScalarKind right) {
    if (left == null) return right;
    if (right == null || left == right) return left;
    return STRING;
  }
}
