package blater.nq.runner.sql.dml.mapping;

import blater.nq.runner.sql.domain.DmlExecutionResult;

import java.util.Map;

/*
 * Responsibility: Applies database-assigned DML result values to the
 * hierarchy nodes registered for write-back.
 */
public class DbSetValueWriter {
  private DbSetValueWriter() {}

  public static void write(DbAssignedNode node, DmlExecutionResult result) {
    Map<String, String> values = result.dbAssignedValues();
    if (values.isEmpty()) return;

    String value = values.get(node.columnName());
    if (value == null) return;
    node.node().setValue(value);
  }

}
