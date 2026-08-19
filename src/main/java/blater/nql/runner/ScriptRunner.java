package blater.nql.runner;

import blater.nql.domain.Hierarchy;
import blater.nql.execution.EngineInputLoader;
import blater.nql.parser.script.NestScript;
import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.Capture;
import blater.nql.runner.sql.dml.*;
import blater.nql.runner.sql.dml.mapping.InputFileRowMapper;
import blater.nql.runner.sql.dml.mapping.MappingResult;
import blater.nql.runner.sql.domain.DmlExecutionResult;
import blater.nql.runner.sql.domain.SqlRow;
import blater.nql.runner.sql.query.RunQuery;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static blater.nql.util.ValueUtil.has;

// `AGENTS MUST  NOT REMOVE *ANY* COMMENTS
/*
 * Responsibility: Dispatches an already parsed script against the
 * active SQL connection and optional file input.
 */
public final class ScriptRunner {
  private ScriptRunner() { }

  public static Hierarchy run(NestScript script, Map<String, String> params) {
    if (script == null || script.statements().isEmpty())
      return null;

    SqlExecutor sqlExecutor = new SqlExecutor(params);
    try {
      return run(script, params, sqlExecutor);
    } finally {
      sqlExecutor.close();
    }
  }

  public static Hierarchy run(
      NestScript script,
      Map<String, String> params,
      SqlExecutor sqlExecutor) {
    if (script == null || script.statements().isEmpty())
      return null;

    final InputFileRowMapper inputFileRowMapper = new InputFileRowMapper();
    Hierarchy inputHierarchy = null;
    Map<String, List<Map<String, Object>>> captureRowSets = new HashMap<>();
    Hierarchy hierarchy =  null;

    for (NestStatement stmt : script.statements()) {
      switch (stmt.getType()) {
        case AUTOCOMMIT ->  sqlExecutor.setAutoCommit(has(stmt.getTargetName()) && stmt.getTargetName().equals("true"));

        case CAPTURE -> captureRowSets.putAll(Capture.captureTempRowset(stmt, params, sqlExecutor));

        case CATALOG -> hierarchy = sqlExecutor.catalog(stmt.getCatalogPattern());

        case SELECT -> hierarchy = RunQuery.runQuery(stmt, params, hierarchy, sqlExecutor);

        case LITERAL -> RunLiteralSql.execute(stmt, params, sqlExecutor);

        case INSERT, UPDATE, DELETE, PROC -> {
          if (inputDataIsFromFile(stmt)) {
            if (inputHierarchy == null)  {
              inputHierarchy = EngineInputLoader.load(params);
            }
            runDmlForInputFile(stmt, inputHierarchy, params, inputFileRowMapper, sqlExecutor);

          } else {
            // use rows captured from a preceding 'capture' statement; each is mapped to a SqlRow & DML run with it
            List<Map<String, Object>> rows = captureRowSets.get(stmt.getSourceRowsetName());
            if (rows == null)
              Log.fatal(IllegalArgumentException.class, "No temp rowset named: " + stmt.getSourceRowsetName());

            // run the statement against each captured row one by one
            // annoying for more than a couple of dozen rows, bad for > 1k rows, unusable for >10k
            //  todo:
            //   add captures at time of capture into in-memory temp table & reformulate the dml
            //   statement dynamically to reference the temp table.
            //   for small row sets, similar or less efficient; for >1K rows, hundreds of times more efficient;
            //   for >100K rows, thousands of times more efficient
            for (Map<String, Object> capturedRow : rows)
              runDml(stmt, Capture.toSqlRow(stmt.getMappings(), capturedRow, params), sqlExecutor);
          }
        }
      }
    }
    if (inputHierarchy != null) {
      return inputHierarchy;
    }
    // Refactor note: callers expect DML-only scripts to return an empty hierarchy, not null.
    return hierarchy == null ? new Hierarchy() : hierarchy;
  }

  private static boolean inputDataIsFromFile(NestStatement stmt) {
    return stmt.getSourceRowsetName() == null;
  }

  private static void runDmlForInputFile(NestStatement stmt, Hierarchy inputDataFile, Map<String, String> parameters, InputFileRowMapper inputFileRowMapper, SqlExecutor sqlExecutor) {
    MappingResult mapping = inputFileRowMapper.map(inputDataFile, stmt.getMappings(), stmt.getReturnMappings(), parameters);
    if (mapping.hasProblem()) {
      sqlExecutor.checkStatementError(mapping.problemStatus(), stmt.getErrorHandling());
      return;
    }

    for (SqlRow row : mapping.rows()) {
      DmlExecutionResult result = runDml(stmt, row, sqlExecutor);
      inputFileRowMapper.applyWriteBack(row, result);
    }
  }




  private static DmlExecutionResult runDml(NestStatement stmt, SqlRow row, SqlExecutor sqlExecutor) {
    return switch (stmt.getType()) {
      case INSERT -> RunInsert.execute(stmt, row, sqlExecutor);
      case UPDATE -> RunUpdate.execute(stmt, row, sqlExecutor);
      case DELETE -> {
        RunDelete.execute(stmt, row, sqlExecutor);
        yield DmlExecutionResult.EMPTY;
      }
      case PROC -> RunProcedure.execute(stmt, row, sqlExecutor);
      default -> Log.fatal(IllegalStateException.class, "executeDml called with non-DML type: " + stmt.getType());
    };
  }
}
