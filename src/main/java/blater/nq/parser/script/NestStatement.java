package blater.nq.parser.script;

import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.domain.MappingPlan;
import lombok.Getter;

import java.util.List;

import static blater.nq.parser.script.ErrorBehaviourType.FAIL;
import static blater.nq.parser.script.NestSqlStatementType.SELECT;

// Responsibility: Holds one parsed HiQL statement body plus mutable handler policy attached after parsing.
@Getter
public class NestStatement {
  private final NestSqlStatementType type;
  private String targetName = null;
  private String sourceRowsetName = null;
  private final String sql;
  private List<InputToColumnMap> mappings = null;
  private List<ReturnMapping> returnMappings = null;
  private final MappingPlan plan;
  private final SelectBlueprint selectBlueprint;
  private final String namespace;
  private final String catalogPattern;
  private ErrorStrategy errorHandling = new ErrorStrategy(FAIL, FAIL);

  /**
   * SELECTS, includes mappings
   */
  private NestStatement(
      NestSqlStatementType type,
      String sql,
      MappingPlan plan,
      String namespace,
      SelectBlueprint selectBlueprint) {
    this.type = type;
    this.sql = sql;
    this.plan = plan;
    this.selectBlueprint = selectBlueprint;
    this.namespace = namespace;
    this.catalogPattern = null;
  }

  /**
   * Non-SELECT statements: no projection payload.
   */
  private NestStatement(NestSqlStatementType type, String targetName, String sourceRowsetName,
                        String sql, List<InputToColumnMap> mappings, List<ReturnMapping> returnMappings) {
    this.type = type;
    this.targetName = targetName;
    this.sourceRowsetName = sourceRowsetName;
    this.sql = sql;
    this.mappings = mappings;
    this.returnMappings = returnMappings;
    this.plan = null;
    this.selectBlueprint = null;
    this.namespace = null;
    this.catalogPattern = null;
  }

  private NestStatement(String catalogPattern) {
    this.type = NestSqlStatementType.CATALOG;
    this.sql = null;
    this.plan = null;
    this.selectBlueprint = null;
    this.namespace = null;
    this.catalogPattern = catalogPattern;
  }

  // INSERT/UPDATE/DELETE writing mapped rows to a table, optionally sourced from a temp rowset.
  public static NestStatement dml(NestSqlStatementType type, String targetName, String sourceRowsetName,
                                  List<InputToColumnMap> mappings, List<ReturnMapping> returnMappings) {
    return new NestStatement(type, targetName, sourceRowsetName, null, mappings, returnMappings);
  }

  // EXEC of a stored procedure with mapped input columns.
  public static NestStatement proc(String targetName, List<InputToColumnMap> mappings) {
    return new NestStatement(NestSqlStatementType.PROC, targetName, null, null, mappings, List.of());
  }

  // SELECT query whose rows project into an output hierarchy.
  public static NestStatement select(String sql, MappingPlan plan, String namespace) {
    return new NestStatement(SELECT, sql, plan, namespace, null);
  }

  public static NestStatement select(
      String sql,
      MappingPlan plan,
      String namespace,
      SelectBlueprint selectBlueprint) {
    return new NestStatement(SELECT, sql, plan, namespace, selectBlueprint);
  }

  public NestStatement compiledSelect(String compiledSql, MappingPlan compiledPlan) {
    return new NestStatement(SELECT, compiledSql, compiledPlan, namespace, selectBlueprint);
  }

  public static NestStatement catalog(String tablePattern) {
    return new NestStatement(tablePattern);
  }

  // CAPTURE of a query result into an in-memory temp rowset named by targetName.
  public static NestStatement capture(String targetName, String sql) {
    return new NestStatement(NestSqlStatementType.CAPTURE, targetName, null, sql, List.of(), List.of());
  }

  public static NestStatement autocommit(String targetName) {
    return new NestStatement(NestSqlStatementType.AUTOCOMMIT, targetName, null, null, List.of(), List.of());
  }

  // Raw SQL run verbatim.
  public static NestStatement literal(String sql) {
    return new NestStatement(NestSqlStatementType.LITERAL, null, null, sql, List.of(), List.of());
  }

  public static NestStatement noop() {
    return new NestStatement(NestSqlStatementType.NOOP, null, null, null, List.of(), List.of());
  }


  public boolean isSelectProducingOutput() {
    // If the plan root isn't empty, it is going to produce hierarchical output.
    return getPlan().rootName() != null;
  }

  public NestStatement setErrorHandling(ErrorStrategy errorHandling) {
    if (errorHandling == null) {
      errorHandling = new ErrorStrategy(FAIL, FAIL);
    }
    this.errorHandling = errorHandling;
    return this;
  }
}
