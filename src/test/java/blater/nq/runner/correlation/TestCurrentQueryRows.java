package blater.nq.runner.correlation;

import blater.nq.runner.sql.domain.QueryResultRow;
import blater.nq.domain.SqlType;
import blater.nq.runner.sql.domain.QueryColumn;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class TestCurrentQueryRows {
    private TestCurrentQueryRows() {
    }

    public static QueryResultRow row(Map<String, String> values) {
        return row(values, Map.of());
    }

    public static QueryResultRow row(
        Map<String, String> values,
        Map<String, Boolean> nullValues) {
        var normalizedValues = normalize(values);
        var normalizedNullValues = normalize(nullValues);
        Map<String, QueryColumn> cells = new HashMap<>();
        normalizedValues.forEach((name, val) -> cells.put(name, cell(
            name,
            val,
            SqlType.STRING,
            normalizedNullValues.getOrDefault(name, false))));
        return new QueryResultRow(cells);
    }

    /** Build a row with typed cell values. SqlType is inferred from each value's runtime type. */
    public static QueryResultRow typedRow(
        Map<String, Object> values,
        Map<String, Boolean> nullValues)
    {
        Map<String, QueryColumn> cells = new HashMap<>();
        values.forEach((name, val) -> {
            String columnKey = key(name);
            cells.put(columnKey, cell(
                columnKey,
                val,
                inferSqlType(val),
                nullValues.getOrDefault(columnKey, false)));
        });
        return new QueryResultRow(cells);
    }

    /*
     * Builds one live QueryColumn cell. A null column value models SQL NULL.
     */
    private static QueryColumn cell(String name, Object value, SqlType sqlType, boolean wasNull) {
        Object columnValue = wasNull ? null : value;
        return QueryColumn.builder()
            .columnName(name)
            .sqlType(sqlType)
            .columnValue(columnValue)
            .build();
    }

    public static Map<String, String> values(String... pairs) {
        var values = new HashMap<String, String>();
        for (var idx = 0; idx < pairs.length; idx += 2) {
            values.put(key(pairs[idx]), pairs[idx + 1]);
        }
        return values;
    }

    private static SqlType inferSqlType(Object value) {
        if (value == null) return SqlType.STRING;
        if (value instanceof String) return SqlType.STRING;
        if (value instanceof Integer) return SqlType.INTEGER;
        if (value instanceof Long) return SqlType.LONG;
        if (value instanceof Short) return SqlType.SHORT;
        if (value instanceof Float) return SqlType.FLOAT;
        if (value instanceof Double) return SqlType.DOUBLE;
        if (value instanceof BigDecimal) return SqlType.NUMBER;
        if (value instanceof LocalDateTime) return SqlType.DATE;
        return SqlType.STRING;
    }

    private static <T> Map<String, T> normalize(Map<String, T> values) {
        var normalized = new HashMap<String, T>();
        values.forEach((name, val) -> normalized.put(key(name), val));
        return normalized;
    }

    private static String key(String columnName) {
        return columnName.toLowerCase(Locale.ROOT);
    }
}
