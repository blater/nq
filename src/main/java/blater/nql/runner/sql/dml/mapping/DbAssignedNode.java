package blater.nql.runner.sql.dml.mapping;

import blater.nql.domain.Node;

/*
 * Responsibility: Identifies one input node that should receive a
 * database-assigned value for a named SQL column.
 */
public record DbAssignedNode(
  Node node,
  String columnName
) {}
