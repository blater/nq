package blater.nq.util;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.report.DiagnosticEnvelope;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

@Slf4j
/*
 * Responsibility: Provides the project logging facade over SLF4J.
 */
public class Log {
  private static final String MESSAGE_PREFIX = "- ";
  private static final ThreadLocal<Boolean> DEBUG = ThreadLocal.withInitial(() -> false);
  private static final ThreadLocal<DiagnosticSink> DIAGNOSTICS = new ThreadLocal<>();

  public static DebugScope withDebug(boolean enabled) {
    boolean previous = DEBUG.get();
    DEBUG.set(enabled);
    return () -> {
      if (previous) {
        DEBUG.set(true);
      } else {
        DEBUG.remove();
      }
    };
  }

  public static DiagnosticScope withDiagnostics(DiagnosticSink sink) {
    DiagnosticSink previous = DIAGNOSTICS.get();
    DIAGNOSTICS.set(java.util.Objects.requireNonNull(sink, "sink"));
    return () -> {
      if (previous == null) {
        DIAGNOSTICS.remove();
      } else {
        DIAGNOSTICS.set(previous);
      }
    };
  }

  public static final Class<? extends Throwable> FATAL_SYNTAX_ERROR = HiqlSyntaxException.class;

  public static void debug(String msg, Object... args) {
    if (DEBUG.get()) diagnostic("debug", msg, args);
  }
  public static void info(String msg, Object... args) { diagnostic("info", msg, args); }
  public static void warn(String msg, Object... args) { diagnostic("warning", msg, args); }
  public static void error(String msg, Object... args) { diagnostic("error", msg, args); }

  @SuppressWarnings("unchecked")
  private static <R, T extends Throwable> R sneakyThrow(Throwable t) throws T {
    throw (T) t; // trick to throw checked without declaration
  }
  public static void fatal(String message) {
    fatal(IllegalStateException.class, message);
  }

  public static <R, T extends Throwable> R fatal(Class<T> type, String message) {
    return sneakyThrow(createException(type, message, null));
  }

  public static <R, T extends Throwable> R fatal(Class<T> type, String message, Throwable cause) {
    return sneakyThrow(createException(type, message, cause));
  }

  private static <T extends Throwable> T createException(Class<T> type, String message, Throwable cause) {
    if (type == IllegalStateException.class) {
      return type.cast(new IllegalStateException(message, cause));
    }
    if (type == IllegalArgumentException.class) {
      return type.cast(new IllegalArgumentException(message, cause));
    }
    if (type == SQLException.class) {
      return type.cast(new SQLException(message, cause));
    }
    if (type == HiqlSyntaxException.class) {
      HiqlSyntaxException ex = new HiqlSyntaxException(message);
      if (cause != null) {
        ex.initCause(cause);
      }
      return type.cast(ex);
    }
    RuntimeException ex = new RuntimeException(message, cause);
    if (type.isInstance(ex)) {
      return type.cast(ex);
    }
    throw ex;
  }

  private static void diagnostic(String level, String message, Object... args) {
    DiagnosticSink sink = DIAGNOSTICS.get();
    if (sink != null) {
      DiagnosticEnvelope.Level envelopeLevel = switch (level) {
        case "debug" -> DiagnosticEnvelope.Level.DEBUG;
        case "info" -> DiagnosticEnvelope.Level.INFO;
        case "warning" -> DiagnosticEnvelope.Level.WARNING;
        default -> DiagnosticEnvelope.Level.ERROR;
      };
      sink.write(new DiagnosticEnvelope(
          "NQ-RUNTIME-" + level.toUpperCase(java.util.Locale.ROOT),
          envelopeLevel,
          interpolate(message, args),
          new DiagnosticEnvelope.Usage.None()));
      return;
    }
    switch (level) {
      case "debug", "info" -> log.info(
          MESSAGE_PREFIX + ("debug".equals(level) ? "DEBUG: " : "") + message, args);
      case "warning" -> log.warn(MESSAGE_PREFIX + message, args);
      default -> log.error(MESSAGE_PREFIX + message, args);
    }
  }

  private static String interpolate(String message, Object... args) {
    String rendered = message;
    for (Object arg : args) {
      if (arg instanceof Throwable) continue;
      int placeholder = rendered.indexOf("{}");
      if (placeholder < 0) break;
      rendered = rendered.substring(0, placeholder)
          + String.valueOf(arg)
          + rendered.substring(placeholder + 2);
    }
    return rendered;
  }

  @FunctionalInterface
  public interface DebugScope extends AutoCloseable {
    @Override
    void close();
  }

  @FunctionalInterface
  public interface DiagnosticScope extends AutoCloseable {
    @Override
    void close();
  }

  @FunctionalInterface
  public interface DiagnosticSink {
    void write(DiagnosticEnvelope diagnostic);
  }
}
