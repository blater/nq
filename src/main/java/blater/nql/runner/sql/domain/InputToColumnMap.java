package blater.nql.runner.sql.domain;

import blater.nql.domain.SqlType;

/*
 * Responsibility: For mapping a xpath defined field from an input data file into insert/update statements
 * Holds the source xpath and what sql table/column it maps to for one XML-to-SQL column mapping.
 * TODO: confirm how does it know what table??
 */
public record InputToColumnMap(
    ColumnDefinition columnDefinition,
    String xpathMapping,
    String defaultValue,
    boolean literal) {

  public static InputToColumnMap newInstance(String sqlName, String xpathMapping, boolean literal) {
    var columnDefinition = new ColumnDefinition(
        sqlName,
        SqlType.STRING              /*type*/,
        ""                          /*sqlFunction*/,
        false                       /*key*/,
        ColumnDefinition.NOT_A_KEY  /*keyNumber*/,
        ColumnDataSourceType.NORMAL /* role */
    );
    return new InputToColumnMap(columnDefinition, xpathMapping, null, literal);
  }
}
