package blater.nql.runner.sql.dml.mapping;

import blater.nql.domain.Hierarchy;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.runner.sql.domain.DmlExecutionResult;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.runner.sql.domain.SqlRow;

import java.util.List;
import java.util.Map;

/** Coordinates hierarchy selection, row-context inference, and DML row assembly. */
public class InputFileRowMapper {
  public MappingResult map(
      Hierarchy input,
      List<InputToColumnMap> mappings,
      Map<String, String> parameters) {

    return map(input, mappings, List.of(), parameters);
  }

  public MappingResult map(
      Hierarchy input,
      List<InputToColumnMap> mappings,
      List<ReturnMapping> returnMappings,
      Map<String, String> parameters) {

    InputMappingSelection selection = InputPathSelector.select(
        input, mappings, returnMappings, parameters);
    if (selection.problem() != null) {
      return new MappingResult(List.of(), selection.problem());
    }

    RowContextResolution context = RowContextResolver.resolve(selection.columns());
    if (context.problem() != null) {
      return new MappingResult(List.of(), context.problem());
    }
    return SqlRowAssembler.assemble(selection, parameters, context.candidate());
  }

  public void applyWriteBack(SqlRow row, DmlExecutionResult result) {
    for (DbAssignedNode node : row.getWriteBackNodes()) {
      DbSetValueWriter.write(node, result);
    }
  }

  public List<DbAssignedNode> registeredWriteBackNodes(SqlRow row) {
    return row.getWriteBackNodes();
  }
}
