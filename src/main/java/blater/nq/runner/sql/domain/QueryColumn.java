package blater.nq.runner.sql.domain;

import blater.nq.domain.SqlType;
import lombok.*;

import java.sql.Timestamp;

@AllArgsConstructor
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
/*
 * Responsibility: Tracks one JDBC result-column value across cursor
 * fetches so row snapshots can report value changes.
 */
public class QueryColumn {
  private String columnName;
  private int columnType;
  private SqlType sqlType;
  private Object previousValue;
  private Object columnValue;
  private int columnIndex;

  public QueryColumn(final String columnName, final int columnType, final int columnIndex) {
    this.columnName = columnName;
    this.columnType = columnType;
    this.sqlType = SqlType.fromJdbcType(columnType);
    this.columnIndex = columnIndex;
  }

  public void setValue(Object value) {
    if (columnValue != null) {
      previousValue = columnValue;
    }
    columnValue = (value instanceof Timestamp ts) ? ts.toLocalDateTime() : value;
  }

  public boolean hasChanged() {
    return (previousValue != null || columnValue != null)
            && (previousValue == null || columnValue != null)
            && (previousValue == null || !previousValue.equals(columnValue));
  }

  public boolean columnValueIsNull() {
    return columnValue == null;
  }
}
