package blater.nq.runner.sql.dml.mapping;

import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.SqlRow;

import java.util.List;

/*
 * Responsibility: Carries either SQL rows produced by DML input
 * mapping or the problem that prevented row construction.
 */
public class MappingResult{
  List<SqlRow> rows;
  SyntaxErrorType problemStatus;

  public MappingResult(List<SqlRow> rows) {
    this(rows, SyntaxErrorType.OK);
  }

  public MappingResult(List<SqlRow> rows, SyntaxErrorType problemStatus) {
    // Refactor note: this class used to be generated-style data; keep constructor assignment explicit.
    this.rows = List.copyOf(rows);
    this.problemStatus = problemStatus == null ? SyntaxErrorType.OK : problemStatus;
  }

  public boolean hasProblem() {
    return problemStatus != SyntaxErrorType.OK;
  }

  public SyntaxErrorType problemStatus() {
    return problemStatus;
  }
  public List<SqlRow> rows() {
    return rows;
  }
}
