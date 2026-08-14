package blater.nq.domain;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/** Normalizes JDBC values so semantically equal structure keys compare equally. */
final class StructureKeyValue {
  private StructureKeyValue() {
  }

  static Object normalize(Object value) {
    return switch (value) {
      case BigDecimal decimal -> decimal.stripTrailingZeros();
      case Byte number -> BigDecimal.valueOf(number.longValue());
      case Short number -> BigDecimal.valueOf(number.longValue());
      case Integer number -> BigDecimal.valueOf(number.longValue());
      case Long number -> BigDecimal.valueOf(number);
      case Float number -> finite(number.doubleValue());
      case Double number -> finite(number);
      case Date date -> date.toLocalDate();
      case Time time -> time.toLocalTime();
      case Timestamp timestamp -> timestamp.toLocalDateTime();
      case Boolean ignored -> value;
      case String ignored -> value;
      case UUID ignored -> value;
      case LocalDate ignored -> value;
      case LocalTime ignored -> value;
      case LocalDateTime ignored -> value;
      case null -> null;
      default -> throw new IllegalArgumentException(
          "Unsupported structure key value type: " + value.getClass().getName());
    };
  }

  private static BigDecimal finite(double value) {
    if (!Double.isFinite(value)) {
      throw new IllegalArgumentException("Non-finite floating-point key value");
    }
    return BigDecimal.valueOf(value);
  }
}
