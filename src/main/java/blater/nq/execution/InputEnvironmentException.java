package blater.nq.execution;

/** The process environment cannot safely resolve an implicit input source. */
public final class InputEnvironmentException extends RuntimeException {
  public InputEnvironmentException(String message) {
    super(message);
  }
}
