package blater.nql.runner.sql.dml.mapping;

import blater.nql.domain.Node;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.runner.SyntaxErrorType;
import blater.nql.runner.sql.domain.InputToColumnMap;

import java.util.List;

record InputMappingSelection(
    List<ColumnSelection> columns,
    List<ReturnSelection> returns,
    SyntaxErrorType problem) {
}

record ColumnSelection(InputToColumnMap mapping, List<SelectedValue> values) {
}

record SelectedValue(String rawValue, Node element, DbAssignedNode writeBackNode) {
}

record ReturnSelection(ReturnMapping mapping, List<SelectedValue> values) {
}

record NodeOccurrence(String id, String pattern, Node element) {
}

record RowContextCandidate(String pattern, List<NodeOccurrence> contexts) {
  int depth() {
    return (int) pattern.chars().filter(ch -> ch == '/').count();
  }
}

record RowContextResolution(RowContextCandidate candidate, SyntaxErrorType problem) {
}
