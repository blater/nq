package blater.nql.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;

/*
 * Responsibility: Maps JDBC types to HiQL value types and coerces
 * raw values for comparison and SQL binding.
 */
public enum SqlType {
  STRING, INTEGER, LONG, SHORT, FLOAT, DOUBLE, NUMBER, BOOLEAN, DATE;

  public boolean isQuoted() {
    return this == STRING || this == DATE;
  }

  public Object parse(String s) {
    return switch (this) {
      case STRING  -> s;
      case INTEGER -> Integer.valueOf(s);
      case LONG    -> Long.valueOf(s);
      case SHORT   -> Short.valueOf(s);
      case FLOAT   -> Float.valueOf(s);
      case DOUBLE  -> Double.valueOf(s);
      case NUMBER  -> new BigDecimal(s);
      case BOOLEAN -> Boolean.valueOf(s);
      case DATE    -> LocalDateTime.parse(s, DateFormats.TIMESTAMP);
    };
  }

  public Object cast(Object value) {
    if (value == null) {
      return null;
    }
    return switch (this) {
      case STRING -> value.toString();
      case INTEGER -> value instanceof Number n ? n.intValue() : Integer.valueOf(value.toString());
      case LONG -> value instanceof Number n ? n.longValue() : Long.valueOf(value.toString());
      case SHORT -> value instanceof Number n ? n.shortValue() : Short.valueOf(value.toString());
      case FLOAT -> value instanceof Number n ? n.floatValue() : Float.valueOf(value.toString());
      case DOUBLE -> value instanceof Number n ? n.doubleValue() : Double.valueOf(value.toString());
      case NUMBER -> toBigDecimal(value);
      case BOOLEAN -> value instanceof Boolean bool ? bool : Boolean.valueOf(value.toString());
      case DATE -> toLocalDateTime(value);
    };
  }

  public static SqlType fromJdbcType(int t) {
    return switch (t) {
      case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR -> STRING;
      case Types.INTEGER  -> INTEGER;
      case Types.BIGINT   -> LONG;
      case Types.SMALLINT -> SHORT;
      case Types.REAL     -> FLOAT;
      case Types.DOUBLE   -> DOUBLE;
      case Types.DECIMAL, Types.NUMERIC -> NUMBER;
      case Types.BOOLEAN, Types.BIT -> BOOLEAN;
      case Types.DATE, Types.TIME, Types.TIMESTAMP -> DATE;
      default -> STRING;
    };
  }

  private BigDecimal toBigDecimal(Object value) {
    if (value instanceof BigDecimal bd) return bd;
    if (value instanceof BigInteger bi) return new BigDecimal(bi);
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
      return BigDecimal.valueOf(((Number) value).longValue());
    }
    if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    return new BigDecimal(value.toString());
  }

  private LocalDateTime toLocalDateTime(Object value) {
    if (value instanceof LocalDateTime ldt) return ldt;
    if (value instanceof Timestamp ts) return ts.toLocalDateTime();
    if (value instanceof Date date) return date.toLocalDate().atStartOfDay();
    if (value instanceof Time time) return time.toLocalTime().atDate(LocalDate.of(1970, 1, 1));
    if (value instanceof java.util.Date date) {
      return new Timestamp(date.getTime()).toLocalDateTime();
    }
    return LocalDateTime.parse(value.toString(), DateFormats.TIMESTAMP);
  }
}
