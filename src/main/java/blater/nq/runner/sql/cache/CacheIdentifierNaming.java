package blater.nq.runner.sql.cache;

import blater.nq.util.Log;

import java.util.Locale;
import java.util.Set;

final class CacheIdentifierNaming {
  private static final Set<String> RESERVED_WORDS = Set.of(
      "ALL", "ALTER", "AND", "AS", "BY", "CREATE", "DELETE", "DROP", "FALSE",
      "FROM", "FULL", "GROUP", "INNER", "INSERT", "INTO", "JOIN", "KEY", "LEFT",
      "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER", "PRIMARY", "RIGHT", "ROW",
      "SELECT", "SET", "TABLE", "TRUE", "UPDATE", "USER", "VALUES", "WHERE");

  private CacheIdentifierNaming() { }

  static String render(String logicalName, String kind) {
    validate(logicalName, kind);
    if (isSimple(logicalName) && !isReserved(logicalName)) return logicalName;
    return "\"" + logicalName.replace("\"", "\"\"") + "\"";
  }

  static String sqlIdentity(String logicalName) {
    if (isSimple(logicalName) && !isReserved(logicalName)) {
      return "U:" + logicalName.toUpperCase(Locale.ROOT);
    }
    return "Q:" + logicalName;
  }

  private static void validate(String logicalName, String kind) {
    if (logicalName == null || logicalName.isEmpty()) {
      Log.fatal(IllegalArgumentException.class, "Cache " + kind + " name is empty.");
    }
    for (int index = 0; index < logicalName.length(); index++) {
      if (Character.isISOControl(logicalName.charAt(index))) {
        Log.fatal(IllegalArgumentException.class,
            "Cache " + kind + " name contains unsupported control characters: " + logicalName);
      }
    }
  }

  private static boolean isSimple(String value) {
    if (value == null || value.isEmpty()) return false;
    if (!asciiLetter(value.charAt(0)) && value.charAt(0) != '_') return false;
    for (int index = 1; index < value.length(); index++) {
      char ch = value.charAt(index);
      if (!asciiLetter(ch) && !asciiDigit(ch) && ch != '_') return false;
    }
    return true;
  }

  private static boolean asciiLetter(char ch) {
    return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
  }

  private static boolean asciiDigit(char ch) {
    return ch >= '0' && ch <= '9';
  }

  private static boolean isReserved(String value) {
    return RESERVED_WORDS.contains(value.toUpperCase(Locale.ROOT));
  }
}
