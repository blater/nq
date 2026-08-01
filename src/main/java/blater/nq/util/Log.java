package blater.nq.util;

import blater.nq.parser.HiqlSyntaxException;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;

@Slf4j
/*
 * Responsibility: Provides the project logging facade over SLF4J.
 */
public class Log {
  private static final String MESSAGE_PREFIX = "- ";
  private static final boolean THROW_FATAL_ERRORS = Boolean.getBoolean("nq.debug");
  private static boolean isDebug;

  public static void debug(boolean val) {
    Log.isDebug = val;
  }

  public static final Class<? extends Throwable> FATAL_SYNTAX_ERROR = HiqlSyntaxException.class;

  public static void debug(String msg, Object... args) {
    if (isDebug) log.info(MESSAGE_PREFIX + "DEBUG: " + msg, args);
  }
  public static void info(String msg, Object... args) { log.info(MESSAGE_PREFIX + msg, args); }
  public static void warn(String msg, Object... args) { log.warn(MESSAGE_PREFIX + msg, args); }
  public static void error(String msg, Object... args) { log.error(MESSAGE_PREFIX + msg, args); }

  @SuppressWarnings("unchecked")
  private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
    throw (T) t; // trick to throw checked without declaration
  }
  private static <R, T extends Throwable> R exit(int status) {
    System.exit(status);
    return null;
  }

  public static void fatal(String message) {
    fatal(IllegalStateException.class, message);
  }

  public static <R, T extends Throwable> R fatal(Class<T> type, String message) {
    if (THROW_FATAL_ERRORS) {
      sneakyThrow(createException(type, message, null));
    }
    log.error(MESSAGE_PREFIX + message);
    if (isDebug) {
      T ex = createException(type, message, null);
      sneakyThrow(ex);
    }
    return exit(1);
  }

  public static <R, T extends Throwable> R fatal(Class<T> type, String message, Throwable cause) {
    if (THROW_FATAL_ERRORS) {
      sneakyThrow(createException(type, message, cause));
    }
    log.error(MESSAGE_PREFIX + message, cause);
    if (isDebug) {
      T ex = createException(type, message, cause);
      sneakyThrow(ex);
    }
    return exit(1);
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
}
