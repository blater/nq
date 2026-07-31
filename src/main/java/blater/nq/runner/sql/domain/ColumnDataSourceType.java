package blater.nq.runner.sql.domain;

/*
 * Responsibility: Classifies whether a mapped SQL column is supplied
 * normally, generated locally, or refreshed from the database.
 */
public enum ColumnDataSourceType {
  NORMAL,
  GENERATED_UID,
  DB_ASSIGNED
}
