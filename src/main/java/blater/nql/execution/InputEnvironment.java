package blater.nql.execution;

import java.io.InputStream;

/**
 * The process input boundary used when resolving an implicit stdin source.
 *
 * <p>Callers must use {@link #stdinDisposition()} when choosing between stdin
 * and an active-cache fallback. {@link #hasImmediatelyAvailableInput()} is a
 * best-effort hint for an ignored-input warning only; it must never select an
 * input source.</p>
 */
public interface InputEnvironment {
  InputStream stdin();

  StdinDisposition stdinDisposition();

  boolean hasImmediatelyAvailableInput();
}
