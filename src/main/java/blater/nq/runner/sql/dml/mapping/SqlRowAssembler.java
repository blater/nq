package blater.nq.runner.sql.dml.mapping;

import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds SQL rows and their row-local database write-back registrations. */
final class SqlRowAssembler {
  private SqlRowAssembler() {
  }

  static MappingResult assemble(
      InputMappingSelection selection,
      Map<String, String> parameters,
      RowContextCandidate candidate) {

    return candidate == null
        ? singleRow(selection.columns(), selection.returns(), parameters)
        : rowsForContext(selection.columns(), selection.returns(), parameters, candidate);
  }

  private static MappingResult singleRow(
      List<ColumnSelection> selections,
      List<ReturnSelection> returnSelections,
      Map<String, String> parameters) {

    List<SqlColumn> columns = new ArrayList<>(selections.size());
    List<DbAssignedNode> registeredNodes = new ArrayList<>();
    Set<String> assignedColumnNames = new LinkedHashSet<>();
    for (ColumnSelection selection : selections) {
      if (!assignedColumnNames.add(selection.mapping().columnDefinition().sqlName())) {
        return problem(SyntaxErrorType.DUPLICATE_TARGET_COLUMN_ASSIGNMENT);
      }
      if (selection.values().size() > 1) {
        return problem(SyntaxErrorType.UNRESOLVABLE_MULTI_VALUE);
      }
      SelectedValue value = selection.values().isEmpty()
          ? missingValue(selection)
          : selection.values().getFirst();
      addWriteBackNode(registeredNodes, value);
      columns.add(toSqlColumn(selection.mapping(), value, parameters));
    }
    if (!addSingleRowReturnNodes(registeredNodes, returnSelections)) {
      return problem(SyntaxErrorType.UNRESOLVABLE_MULTI_VALUE);
    }
    return new MappingResult(List.of(new SqlRow(columns, registeredNodes)));
  }

  private static MappingResult rowsForContext(
      List<ColumnSelection> selections,
      List<ReturnSelection> returnSelections,
      Map<String, String> parameters,
      RowContextCandidate candidate) {

    List<SqlRow> rows = new ArrayList<>(candidate.contexts().size());
    for (NodeOccurrence context : candidate.contexts()) {
      MappingResult problem = addContextRow(rows, selections, returnSelections, parameters, context);
      if (problem != null) {
        return problem;
      }
    }
    return new MappingResult(rows);
  }

  private static MappingResult addContextRow(
      List<SqlRow> rows,
      List<ColumnSelection> selections,
      List<ReturnSelection> returnSelections,
      Map<String, String> parameters,
      NodeOccurrence context) {

    List<SqlColumn> columns = new ArrayList<>(selections.size());
    List<DbAssignedNode> registeredNodes = new ArrayList<>();
    Set<String> assignedColumnNames = new LinkedHashSet<>();
    for (ColumnSelection selection : selections) {
      if (!assignedColumnNames.add(selection.mapping().columnDefinition().sqlName())) {
        return problem(SyntaxErrorType.DUPLICATE_TARGET_COLUMN_ASSIGNMENT);
      }
      List<SelectedValue> matchedValues = matchedValues(selection, context);
      if (matchedValues.size() > 1) {
        return problem(SyntaxErrorType.UNRESOLVABLE_MULTI_VALUE);
      }
      SelectedValue value = matchedValues.isEmpty() ? missingValue(selection) : matchedValues.getFirst();
      addWriteBackNode(registeredNodes, value);
      columns.add(toSqlColumn(selection.mapping(), value, parameters));
    }
    if (!addContextReturnNodes(registeredNodes, returnSelections, context)) {
      return problem(SyntaxErrorType.UNRESOLVABLE_MULTI_VALUE);
    }
    rows.add(new SqlRow(columns, registeredNodes));
    return null;
  }

  private static boolean addSingleRowReturnNodes(
      List<DbAssignedNode> registeredNodes,
      List<ReturnSelection> returnSelections) {

    for (ReturnSelection selection : returnSelections) {
      if (selection.values().size() > 1) {
        return false;
      }
      if (!selection.values().isEmpty()) {
        addWriteBackNode(registeredNodes, selection.values().getFirst());
      }
    }
    return true;
  }

  private static boolean addContextReturnNodes(
      List<DbAssignedNode> registeredNodes,
      List<ReturnSelection> returnSelections,
      NodeOccurrence context) {

    for (ReturnSelection selection : returnSelections) {
      List<SelectedValue> matchedValues = selection.values().stream()
          .filter(value -> value.element() == null
              || RowContextResolver.sameOrDescendant(value.element(), context.element()))
          .toList();
      if (matchedValues.size() > 1) {
        return false;
      }
      if (!matchedValues.isEmpty()) {
        addWriteBackNode(registeredNodes, matchedValues.getFirst());
      }
    }
    return true;
  }

  private static List<SelectedValue> matchedValues(
      ColumnSelection selection,
      NodeOccurrence context) {

    return selection.values().stream()
        .filter(value -> RowContextResolver.isRelated(selection, value, context))
        .toList();
  }

  private static SelectedValue missingValue(ColumnSelection selection) {
    return new SelectedValue(selection.mapping().defaultValue(), null, null);
  }

  private static SqlColumn toSqlColumn(
      InputToColumnMap mapping,
      SelectedValue value,
      Map<String, String> parameters) {

    return SqlColumn.from(mapping.columnDefinition(), value.rawValue(), parameters);
  }

  private static void addWriteBackNode(List<DbAssignedNode> nodes, SelectedValue value) {
    if (value.writeBackNode() != null) {
      nodes.add(value.writeBackNode());
    }
  }

  private static MappingResult problem(SyntaxErrorType status) {
    return new MappingResult(List.of(), status);
  }
}
