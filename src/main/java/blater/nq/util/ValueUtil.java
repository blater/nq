package blater.nq.util;

import java.util.List;

public class ValueUtil {
  private static final String EMPTY = "";

  public static boolean hasValue(String s) {
    return s != null && !s.isEmpty();
  }
  public static boolean hasValue(List<?> l) {
    return l!=null && !l.isEmpty();
  }


  public static String asString(Object value) {
    return value == null ? EMPTY : value.toString();
  }

  public static boolean has(Object o) {
    return o != null;
  }
}
