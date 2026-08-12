package blater.nq.util;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.report.ReportFormat;
import blater.nq.report.ReportWriter;
import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
/*
 * Responsibility: Provides the project logging facade over SLF4J.
 */
public class Log {
  private static final String MESSAGE_PREFIX = "- ";
  private static final boolean THROW_FATAL_ERRORS = Boolean.getBoolean("nq.debug");
  private static boolean isDebug;
  private static ReportFormat reportFormat;

  public static void debug(boolean val) {
    Log.isDebug = val;
  }

  public static void reportFormat(ReportFormat val) {
    Log.reportFormat = val;
  }

  public static final Class<? extends Throwable> FATAL_SYNTAX_ERROR = HiqlSyntaxException.class;

  public static void debug(String msg, Object... args) {
    if (isDebug) diagnostic("debug", msg, args);
  }
  public static void info(String msg, Object... args) { diagnostic("info", msg, args); }
  public static void warn(String msg, Object... args) { diagnostic("warning", msg, args); }
  public static void error(String msg, Object... args) { diagnostic("error", msg, args); }

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
    diagnostic("error", message);
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
    diagnostic("error", message, cause);
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

  private static void diagnostic(String level, String message, Object... args) {
    if (reportFormat == null) {
      switch (level) {
        case "debug", "info" -> log.info(MESSAGE_PREFIX + ("debug".equals(level) ? "DEBUG: " : "") + message, args);
        case "warning" -> log.warn(MESSAGE_PREFIX + message, args);
        default -> log.error(MESSAGE_PREFIX + message, args);
      }
      return;
    }

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("status", "error".equals(level) ? "error" : "diagnostic");
    report.put("level", level);
    report.put("message", interpolate(message, args));
    ReportWriter.write(report, reportFormat, System.err);
  }

  private static String interpolate(String message, Object... args) {
    String rendered = message;
    for (Object arg : args) {
      if (arg instanceof Throwable) {
        continue;
      }
      int placeholder = rendered.indexOf("{}");
      if (placeholder < 0) {
        break;
      }
      rendered = rendered.substring(0, placeholder)
          + String.valueOf(arg)
          + rendered.substring(placeholder + 2);
    }
    return rendered;
  }
}
