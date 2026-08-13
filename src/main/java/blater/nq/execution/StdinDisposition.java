package blater.nq.execution;

/**
 * How the operating system presents standard input to NQ.
 *
 * <p>This describes the input file descriptor, independently of stdout and
 * stderr. In particular, redirecting stdout must not make terminal stdin look
 * redirected.</p>
 */
public enum StdinDisposition {
  TERMINAL,
  REDIRECTED,
  UNKNOWN
}
