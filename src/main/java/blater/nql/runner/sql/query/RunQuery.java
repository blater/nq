package blater.nql.runner.sql.query;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.MappingPlan;
import blater.nql.runner.inference.KeyInference;
import blater.nql.parser.script.NestStatement;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.runner.sql.SqlRowCursor;
import blater.nql.util.Template;

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
