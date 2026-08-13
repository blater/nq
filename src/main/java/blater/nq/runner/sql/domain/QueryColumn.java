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
 * Responsibility: Holds one JDBC result-column value for the current
 * cursor row.
 */
public class QueryColumn {
  private String columnName;
  private int columnType;
  private SqlType sqlType;
  private Object columnValue;
  private int columnIndex;

  public QueryColumn(final String columnName, final int columnType, final int columnIndex) {
    this.columnName = columnName;
    this.columnType = columnType;
    this.sqlType = SqlType.fromJdbcType(columnType);
    this.columnIndex = columnIndex;
  }

  public void setValue(Object value) {
    columnValue = (value instanceof Timestamp ts) ? ts.toLocalDateTime() : value;
  }

  public boolean columnValueIsNull() {
    return columnValue == null;
  }
}
