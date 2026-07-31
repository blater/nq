package blater.nq.runner.sql.query;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.MappingPlan;
import blater.nq.runner.inference.KeyInference;
import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.SqlRowCursor;
import blater.nq.util.Template;

import java.util.Map;

public class RunQuery {

  public static Hierarchy runQuery(NestStatement stmt, Map<String, String> parameters, Hierarchy outputHierarchy, SqlExecutor sqlExecutor)
  {
    if (outputHierarchy == null)
      outputHierarchy = new Hierarchy();

    NestStatement executable = KeyInference.compile(stmt, parameters, sqlExecutor);

    String querySql = Template.expand(executable.getSql(), parameters);
    try (SqlRowCursor cursor = sqlExecutor.query(querySql)) {
      if (!executable.isSelectProducingOutput()) {
        var blueprint = executable.getSelectBlueprint();
        var columnLabels = cursor.columnLabels();
        var outputNames = blueprint == null ? columnLabels : blueprint.outputNames();
        if (outputNames.size() != columnLabels.size()) outputNames = columnLabels;
        executable = executable.compiledSelect(
            executable.getSql(), MappingPlan.flatRows(columnLabels, outputNames));
      }
      outputHierarchy.register(executable);
      while (cursor.next()) {
        outputHierarchy.readRow(cursor.row());
      }
    }

    return outputHierarchy;
  }

}
