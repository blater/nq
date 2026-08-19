package blater.nql.parser.script;

// Responsibility: Names the kind of one parsed Nest SQL statement; drives execution dispatch.
public enum NestSqlStatementType {
  SELECT,
  CATALOG,
  INSERT,
  UPDATE,
  DELETE,
  PROC,
  CAPTURE,
  LITERAL,
  AUTOCOMMIT,
  NOOP
}
