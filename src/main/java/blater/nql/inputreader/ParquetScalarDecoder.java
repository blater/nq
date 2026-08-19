package blater.nql.inputreader;

import blater.nql.util.Log;
import org.apache.parquet.example.data.GroupValueSource;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Base64;

/** Converts Parquet physical and logical scalar values into nql text values. */
final class ParquetScalarDecoder {
  private ParquetScalarDecoder() {
  }

  static ScalarValue scalarValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      String fieldPath) {

    LogicalTypeAnnotation logical = type.getLogicalTypeAnnotation();
    if (logical instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation
        || logical instanceof LogicalTypeAnnotation.EnumLogicalTypeAnnotation) {
      return new ScalarValue(source.getBinary(fieldIndex, valueIndex).toStringUsingUTF8(), true);
    }
    if (logical instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal) {
      return new ScalarValue(decimalValue(source, fieldIndex, valueIndex, type, decimal).toPlainString(), false);
    }
    if (logical instanceof LogicalTypeAnnotation.DateLogicalTypeAnnotation) {
      return new ScalarValue(LocalDate.ofEpochDay(source.getInteger(fieldIndex, valueIndex)).toString(), false);
    }
    if (logical instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation time) {
      return new ScalarValue(timeValue(source, fieldIndex, valueIndex, type, time), false);
    }
    if (logical instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestamp) {
      return new ScalarValue(timestampValue(source, fieldIndex, valueIndex, timestamp), false);
    }
    if (logical != null && !(logical instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation)) {
      return Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet logical type at " + fieldPath + ": " + logical);
    }
    return primitiveValue(source, fieldIndex, valueIndex, type, fieldPath);
  }

  static String mapKey(
      GroupValueSource entry,
      int keyIndex,
      PrimitiveType keyType,
      String fieldPath) {

    if (entry.getFieldRepetitionCount(keyIndex) == 0) {
      return Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet map with missing key at " + fieldPath);
    }
    String key = scalarValue(entry, keyIndex, 0, keyType, fieldPath + ".key").value();
    if (key == null || key.isBlank()) {
      return Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet map with blank key at " + fieldPath);
    }
    return key;
  }

  private static ScalarValue primitiveValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      String fieldPath) {

    return switch (type.getPrimitiveTypeName()) {
      case BOOLEAN -> new ScalarValue(Boolean.toString(source.getBoolean(fieldIndex, valueIndex)), false);
      case INT32 -> new ScalarValue(Integer.toString(source.getInteger(fieldIndex, valueIndex)), false);
      case INT64 -> new ScalarValue(Long.toString(source.getLong(fieldIndex, valueIndex)), false);
      case FLOAT -> new ScalarValue(Float.toString(source.getFloat(fieldIndex, valueIndex)), false);
      case DOUBLE -> new ScalarValue(Double.toString(source.getDouble(fieldIndex, valueIndex)), false);
      case BINARY, FIXED_LEN_BYTE_ARRAY -> new ScalarValue(
          Base64.getEncoder().encodeToString(source.getBinary(fieldIndex, valueIndex).getBytes()), false);
      case INT96 -> new ScalarValue(
          Base64.getEncoder().encodeToString(source.getInt96(fieldIndex, valueIndex).getBytes()), false);
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet primitive type at " + fieldPath + ": " + type.getPrimitiveTypeName());
    };
  }

  private static BigDecimal decimalValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal) {

    BigInteger unscaled = switch (type.getPrimitiveTypeName()) {
      case INT32 -> BigInteger.valueOf(source.getInteger(fieldIndex, valueIndex));
      case INT64 -> BigInteger.valueOf(source.getLong(fieldIndex, valueIndex));
      case BINARY, FIXED_LEN_BYTE_ARRAY -> new BigInteger(source.getBinary(fieldIndex, valueIndex).getBytes());
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet decimal physical type: " + type.getPrimitiveTypeName());
    };
    return new BigDecimal(unscaled, decimal.getScale());
  }

  private static String timeValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      LogicalTypeAnnotation.TimeLogicalTypeAnnotation time) {

    long raw = type.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT32
        ? source.getInteger(fieldIndex, valueIndex)
        : source.getLong(fieldIndex, valueIndex);
    long nanos = switch (time.getUnit()) {
      case MILLIS -> raw * 1_000_000L;
      case MICROS -> raw * 1_000L;
      case NANOS -> raw;
    };
    return LocalTime.ofNanoOfDay(nanos).toString();
  }

  private static String timestampValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestamp) {

    long raw = source.getLong(fieldIndex, valueIndex);
    long seconds = switch (timestamp.getUnit()) {
      case MILLIS -> raw / 1_000L;
      case MICROS -> raw / 1_000_000L;
      case NANOS -> raw / 1_000_000_000L;
    };
    long nanos = switch (timestamp.getUnit()) {
      case MILLIS -> Math.floorMod(raw, 1_000L) * 1_000_000L;
      case MICROS -> Math.floorMod(raw, 1_000_000L) * 1_000L;
      case NANOS -> Math.floorMod(raw, 1_000_000_000L);
    };
    if (timestamp.isAdjustedToUTC()) {
      return Instant.ofEpochSecond(seconds, nanos).toString();
    }
    return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC).toString();
  }

  record ScalarValue(String value, boolean templateString) {
  }
}
