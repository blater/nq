package blater.nql.runner.sql.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/*
 * Responsibility: Carries database-assigned values returned after one
 * DML row executes.
 */
public record DmlExecutionResult(
    Map<String, String> dbAssignedValues)
{
  public static final DmlExecutionResult EMPTY = new DmlExecutionResult(Map.of());

  public DmlExecutionResult {
    dbAssignedValues = Collections.unmodifiableMap(new LinkedHashMap<>(dbAssignedValues));
  }

  public static DmlExecutionResult of(Map<String, String> dbAssignedValues) {
    return dbAssignedValues.isEmpty() ? EMPTY : new DmlExecutionResult(dbAssignedValues);
  }
}
