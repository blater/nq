package blater.nql.runner.sql.domain;

import blater.nql.parser.script.NestStatement;
import blater.nql.runner.SyntaxErrorType;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
/*
 * Responsibility: Carries built SQL text, bind values, status, and
 * DB-assigned-value follow-up metadata for one DML row.
 */
public class SqlStatement {
  private final SyntaxErrorType status;
  private final String sql;
  private final List<Object> parameters;
  private final String dbAssignedIdentityColumn;
  private final boolean uidInvolved;
  private final String selectDbAssignedValuesSql;
  private final List<Object> selectDbAssignedValuesParameters;
  private final List<String> dbAssignedColumns;

  public SqlStatement(SyntaxErrorType status, String sql) {
    this(status, sql, List.of());
  }

  public SqlStatement(SyntaxErrorType status, String sql, List<Object> parameters) {
    this.status = status;
    this.sql = sql;
    this.parameters = immutableParameters(parameters);
    this.uidInvolved = false;
    this.dbAssignedIdentityColumn = null;
    this.selectDbAssignedValuesSql = null;
    this.selectDbAssignedValuesParameters = List.of();
    this.dbAssignedColumns = List.of();
  }

  public SqlStatement(SyntaxErrorType status,
                      String sql,
                      List<Object> parameters,
                      String dbAssignedIdentityColumn,
                      boolean uidInvolved,
                      SqlRow row,
                      NestStatement stmt
  ) {
    this.status = status;
    this.sql = sql;
    this.parameters = immutableParameters(parameters);
    this.uidInvolved = uidInvolved;
    this.dbAssignedIdentityColumn = dbAssignedIdentityColumn;
    this.dbAssignedColumns = dbAssignedColumns(row);
    this.selectDbAssignedValuesSql = buildDbAssignedValsSql(stmt, row, dbAssignedColumns);
    this.selectDbAssignedValuesParameters = buildDbAssignedValsParameters(row, dbAssignedColumns);
  }

  public boolean hasIdentityColumn() {
    return dbAssignedIdentityColumn != null;
  }

  public String getDbAssignedValuesSql(String generatedKey) {
    if (selectDbAssignedValuesSql == null) {
      return null;
    }
    if (generatedKey == null || dbAssignedIdentityColumn == null) {
      return selectDbAssignedValuesSql;
    }
    String separator = selectDbAssignedValuesSql.contains(" where ") ? " and " : " where ";
    return selectDbAssignedValuesSql
        + separator
        + dbAssignedIdentityColumn
        + " = ?";
  }

  public List<Object> getDbAssignedValuesParameters(String generatedKey) {
    if (generatedKey == null || dbAssignedIdentityColumn == null) {
      return selectDbAssignedValuesParameters;
    }
    List<Object> parameters = new ArrayList<>(selectDbAssignedValuesParameters);
    parameters.add(generatedKey);
    return immutableParameters(parameters);
  }

  public boolean hasDbAssignedColumns() {
    return !dbAssignedColumns.isEmpty();
  }

  private List<String> dbAssignedColumns(SqlRow row) {
    return row.getColumns().stream()
        .filter(SqlColumn::isDbAssigned)
        .map(SqlColumn::sqlName)
        .toList();
  }

  private String buildDbAssignedValsSql(NestStatement stmt, SqlRow row, List<String> dbAssignedColumns) {
    return dbAssignedColumns == null || dbAssignedColumns.isEmpty()
        ? null
        : dbAssignedSelect(row) + " from " + stmt.getTargetName() + dbAssignedWhere(row);
  }

  private String dbAssignedSelect(SqlRow row) {
    StringBuilder select = new StringBuilder("select ");
    boolean first = true;
    for (var col : row.getColumns()) {
      if (col.isDbAssigned()) {
        select.append((first ? "" : ", ")).append(col.sqlName());
        first = false;
      }
    }
    return select.toString();
  }

  private String dbAssignedWhere(SqlRow row) {
    StringBuilder dbAssignedWhere = new StringBuilder();
    boolean first = true;
    for (var column : row.getColumns()) {
      if (!column.isKey() || column.isUid() || column.missingData()) {
        continue;
      }
      dbAssignedWhere
          .append((first ? " where " : " and "))
          .append(column.sqlName())
          .append(" = ")
          .append(column.sqlExpression());
      first = false;
    }
    return dbAssignedWhere.toString();
  }

  private List<Object> buildDbAssignedValsParameters(SqlRow row, List<String> dbAssignedColumns) {
    if (dbAssignedColumns == null || dbAssignedColumns.isEmpty()) {
      return List.of();
    }
    return row.getColumns().stream()
        .filter(column -> column.isKey() && !column.isUid() && !column.missingData())
        .map(SqlColumn::bindValue)
        .toList();
  }

  private static List<Object> immutableParameters(List<Object> parameters) {
    return Collections.unmodifiableList(new ArrayList<>(parameters));
  }
}
