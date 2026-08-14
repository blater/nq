package blater.nq.runner.sql.dml.mapping;

import blater.nq.domain.Node;
import blater.nq.parser.script.ReturnMapping;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.InputToColumnMap;

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
