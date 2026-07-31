package blater.nq.runner.sql;

import blater.nq.parser.script.ErrorStrategy;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.SqlStatement;
import blater.nq.domain.Hierarchy;
import blater.nq.domain.SqlType;
import blater.nq.util.Log;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static blater.nq.parser.script.ErrorBehaviourType.FAIL;
import static blater.nq.runner.SyntaxErrorType.OK;

/*
 * Responsibility: Owns the active JDBC connection and executes SQL
 * through its connection context.
 */
public final class SqlExecutor {
  private final ConnectionContext context = new ConnectionContext();
  private final Path cacheFile;
  private final String cacheIdentity;

  public SqlExecutor(Map<String, String> parameters) {
    this.cacheFile = null;
    this.cacheIdentity = null;
    connect(parameters);
  }

  public SqlExecutor(Map<String, String> parameters, Path cacheFile, String cacheIdentity) {
    this.cacheFile = Objects.requireNonNull(cacheFile);
    this.cacheIdentity = Objects.requireNonNull(cacheIdentity);
    connect(parameters);
  }

  public void connect(Map<String, String> parameters) {
    context.connect(parameters);
  }

  public void close() {
    context.close();
  }

  public void setAutoCommit(boolean autoCommit) {
    try {
      context.connection().setAutoCommit(autoCommit);
    } catch (SQLException ex) {
      Log.fatal(IllegalStateException.class, "Could not set autoCommit: " + ex.getMessage(), ex);
    }
  }

  public SqlRowCursor query(String selectSql) {
    SqlRowCursor sqlCursor = null;
    try {
      Statement statement = context.connection().createStatement();
      sqlCursor = new SqlRowCursor(statement, statement.executeQuery(SqlCommentFilter.filterOutComments(selectSql)));
    } catch (SQLException ex) {
      Log.fatal(SQLException.class, "Error running sql: " + selectSql + ": " + ex.getMessage());
    }
    return sqlCursor;
  }

  public SqlRowCursor query(String selectSql, List<Object> parameters) {
    SqlRowCursor sqlCursor = null;
    try {
      PreparedStatement statement = context.connection().prepareStatement(SqlCommentFilter.filterOutComments(selectSql));
      bind(statement, parameters);
      sqlCursor = new SqlRowCursor(statement, statement.executeQuery());
    } catch (SQLException ex) {
      Log.fatal(SQLException.class, "Error running sql: " + selectSql + ": " + ex.getMessage());
    }
    return sqlCursor;
  }

  public Map<String, SqlType> columnTypes(String tableName) {
    return context.columnTypes(tableName);
  }

  public List<String> columnNames(String tableName) {
    return context.columnNames(tableName);
  }

  public Connection connection() {
    return context.connection();
  }

  public Optional<Path> cacheFile() {
    return Optional.ofNullable(cacheFile);
  }

  public Optional<String> cacheIdentity() {
    return Optional.ofNullable(cacheIdentity);
  }

  public Hierarchy catalog(String tablePattern) {
    return Catalog.read(context.connection(), tablePattern);
  }

  public int runLiteral(String sql, ErrorStrategy errorHandling) {
    return run(sql, List.of(), errorHandling, false).rowCount();
  }

  public int execute(String sql) {
    return execute(sql, List.of());
  }

  public int execute(String sql, List<Object> parameters) {
    return run(sql, parameters, new ErrorStrategy(FAIL, FAIL), false).rowCount();
  }

  public int executeBatch(String sql, List<List<Object>> batchParameters) {
    return runBatch(sql, batchParameters, new ErrorStrategy(FAIL, FAIL));
  }

  public int run(SqlStatement statement, ErrorStrategy errorHandling) {
    return run(statement.getSql(), statement.getParameters(), errorHandling, false).rowCount();
  }

  public String insertWithIdentity(SqlStatement statement, ErrorStrategy errorHandling) {
    return run(statement.getSql(), statement.getParameters(), errorHandling, true).generatedKey();
  }

  public boolean checkStatementError(SyntaxErrorType status, ErrorStrategy errorHandling) {
    if (status == OK)
      return true;

    String message = "DML statement error: " + status;
    rollbackIfRequested(errorHandling.isOnProblemRollback());

    if (errorHandling.getOnProblemBehaviour() == FAIL) {
      Log.fatal(IllegalStateException.class, message);
    } else {
      Log.warn(message);
    }
    return false;
  }

  private void handleExecutionError(String sql, ErrorStrategy errorHandling, Exception ex)
  {
    rollbackIfRequested(errorHandling.isOnErrorRollback());
    if (errorHandling.getOnErrorBehaviour() == FAIL) {
      Log.fatal(IllegalStateException.class, "DML execution error: " + sql, ex);
      return;
    }
    Log.error("Exception running the SQL [{}]: {}", sql, ex.getMessage());
    Log.warn("Continuing after SQL execution error.");
  }

  private SqlExecution run(String sql, List<Object> parameters, ErrorStrategy errorHandling, boolean returnGeneratedKey)
  {
    int rowcount = 0;
    String generatedKey = null;

    sql = SqlCommentFilter.filterOutComments(sql);
    Log.debug("Trace SQL: {}", sql);
    Connection connection = context.connection();
    try {
      PreparedStatement statement = returnGeneratedKey
          ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
          : connection.prepareStatement(sql);
      bind(statement, parameters);

      if (returnGeneratedKey) {
        statement.execute();
        ResultSet rs = statement.getGeneratedKeys();
        if (rs.next()) {
          generatedKey = rs.getString(1);
          Log.debug("Generated UID = {}", generatedKey);
        }
      } else {
        statement.execute();
      }

      rowcount = statement.getUpdateCount();
      statement.close();

      if (rowcount == -1) { // statement was a isSelect...
        rowcount = 1;
      }

    } catch (Exception x) {
      handleExecutionError(sql, errorHandling, x);
    }

    return new SqlExecution(rowcount, generatedKey);
  }

  private int runBatch(String sql, List<List<Object>> batchParameters, ErrorStrategy errorHandling) {
    if (batchParameters == null || batchParameters.isEmpty()) {
      return 0;
    }

    int rowCount = 0;
    sql = SqlCommentFilter.filterOutComments(sql);
    Log.debug("Trace SQL: {}", sql);
    Connection connection = context.connection();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (List<Object> parameters : batchParameters) {
        bind(statement, parameters);
        statement.addBatch();
      }

      for (int count : statement.executeBatch()) {
        if (count > 0) {
          rowCount += count;
        } else if (count == Statement.SUCCESS_NO_INFO) {
          rowCount++;
        }
      }
    } catch (Exception x) {
      handleExecutionError(sql, errorHandling, x);
    }

    return rowCount;
  }

  private void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      statement.setObject(index + 1, parameters.get(index));
    }
  }

  /*
   * Responsibility: Carries the JDBC update count and optional
   * generated key returned by one execution.
   */
  private record SqlExecution(int rowCount, String generatedKey) {
  }

  private void rollbackIfRequested(boolean rollback) {
    if (!rollback) {
      return;
    }

    try {
      context.connection().rollback();
    } catch (SQLException e) {
      Log.error(e.getMessage());
    }
  }

}
