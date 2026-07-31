package blater.nq.runner.sql.dml.mapping;

import blater.nq.domain.Node;

/*
 * Responsibility: Identifies one input node that should receive a
 * database-assigned value for a named SQL column.
 */
public record DbAssignedNode(
  Node node,
  String columnName
) {}
