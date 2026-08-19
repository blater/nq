package blater.nql.runner.sql.dml.mapping;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.parser.script.ReturnMapping;
import blater.nql.runner.SyntaxErrorType;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.util.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static blater.nql.runner.sql.dml.mapping.InputHierarchyPaths.ensureDbAssignedTarget;
import static blater.nql.runner.sql.dml.mapping.InputHierarchyPaths.ensureReturnTargets;
import static blater.nql.runner.sql.dml.mapping.InputHierarchyPaths.needsDbAssignment;
import static blater.nql.runner.sql.dml.mapping.InputHierarchyPaths.selectNodes;

/** Selects mapped hierarchy values and creates missing database write-back targets. */
final class InputPathSelector {
  private InputPathSelector() {
  }

  static InputMappingSelection select(
      Hierarchy input,
      List<InputToColumnMap> mappings,
      List<ReturnMapping> returnMappings,
      Map<String, String> parameters) {

    if (!ensureReturnTargets(input, returnMappings)) {
      return problem();
    }
    List<ColumnSelection> columns = columnSelections(input, mappings, parameters);
    if (columns == null) {
      return problem();
    }
    List<ReturnSelection> returns = returnSelections(input, returnMappings);
    return returns == null
        ? problem()
        : new InputMappingSelection(columns, returns, null);
  }

  private static InputMappingSelection problem() {
    return new InputMappingSelection(List.of(), List.of(), SyntaxErrorType.UNSUPPORTED_SOURCE_PATH);
  }

  private static List<ColumnSelection> columnSelections(
      Hierarchy input,
      List<InputToColumnMap> mappings,
      Map<String, String> parameters) {

    List<ColumnSelection> selections = new ArrayList<>(mappings.size());
    for (InputToColumnMap mapping : mappings) {
      if (mapping.literal()) {
        selections.add(new ColumnSelection(
            mapping,
            List.of(new SelectedValue(Template.expand(mapping.xpathMapping(), parameters), null, null))));
        continue;
      }
      if (!ensureDbAssignedTarget(input, mapping)) {
        return null;
      }
      List<SelectedValue> values = selectedValues(input, mapping);
      if (values == null) {
        return null;
      }
      selections.add(new ColumnSelection(mapping, values));
    }
    return selections;
  }

  private static List<SelectedValue> selectedValues(Hierarchy input, InputToColumnMap mapping) {
    List<Node> nodes = selectNodes(input, mapping.xpathMapping());
    if (nodes == null) {
      return null;
    }
    List<SelectedValue> values = new ArrayList<>(nodes.size());
    for (Node node : nodes) {
      values.add(new SelectedValue(rawValue(node), valueNode(node), writeBackNode(mapping, node)));
    }
    return values;
  }

  private static List<ReturnSelection> returnSelections(
      Hierarchy input,
      List<ReturnMapping> returnMappings) {

    List<ReturnSelection> selections = new ArrayList<>(returnMappings.size());
    for (ReturnMapping mapping : returnMappings) {
      List<Node> nodes = selectNodes(input, mapping.getXpath());
      if (nodes == null) {
        return null;
      }
      List<SelectedValue> values = nodes.stream()
          .filter(InputPathSelector::isWritableTarget)
          .map(node -> new SelectedValue(
              rawValue(node), valueNode(node), new DbAssignedNode(node, mapping.getColumnName())))
          .toList();
      selections.add(new ReturnSelection(mapping, values));
    }
    return selections;
  }

  private static String rawValue(Node node) {
    if (node == null || node.getValue() == null) {
      return "";
    }
    return node.isAttribute() ? node.getValue() : node.getValue().trim();
  }

  private static Node valueNode(Node node) {
    if (node == null) {
      return null;
    }
    return node.isAttribute() ? node.parent() : node;
  }

  private static DbAssignedNode writeBackNode(InputToColumnMap mapping, Node node) {
    if (!needsDbAssignment(mapping)) {
      return null;
    }
    if (mapping.columnDefinition().isUid() && !rawValue(node).isEmpty()) {
      return null;
    }
    return isWritableTarget(node)
        ? new DbAssignedNode(node, mapping.columnDefinition().sqlName())
        : null;
  }

  private static boolean isWritableTarget(Node node) {
    return node != null && (node.isAttribute() || node.parent() != null);
  }

}
