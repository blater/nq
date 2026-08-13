package blater.nq.runner.sql.dml.mapping;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.HierarchyPath;
import blater.nq.domain.Node;
import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.parser.script.ReturnMapping;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.DmlExecutionResult;
import blater.nq.runner.sql.domain.SqlColumn;
import blater.nq.runner.sql.domain.SqlRow;
import blater.nq.util.Log;
import blater.nq.util.Template;

import java.util.*;

// Responsibility: Projects hierarchy input into DML rows and
// row-local database-produced value targets.
public class InputFileRowMapper {
  private static final Set<String> DELIMITED_ROOTS = Set.of("csv", "tsv");

  // Responsibility: Maps hierarchy input to DML rows when the statement has no explicit returns write-back mappings.
  public MappingResult map(Hierarchy input, List<InputToColumnMap> mappings, Map<String, String> parameters)
  {
    return map(input, mappings, List.of(), parameters);
  }

  public MappingResult map(Hierarchy input, List<InputToColumnMap> mappings, List<ReturnMapping> returnMappings, Map<String, String> parameters)
  {
    if (!ensureReturnTargets(input, returnMappings)) {
      return problem(SyntaxErrorType.UNSUPPORTED_SOURCE_PATH);
    }
    List<ColumnSelection> selections = selections(input, mappings, parameters);
    if (selections == null) {
      return problem(SyntaxErrorType.UNSUPPORTED_SOURCE_PATH);
    }
    List<ReturnSelection> returnSelections = returnSelections(input, returnMappings);
    if (returnSelections == null) {
      return problem(SyntaxErrorType.UNSUPPORTED_SOURCE_PATH);
    }

    // key: indexed element path
    Map<String, NodeOccurrence> occurrences = occurrences(selections);

    // key: unindexed element-name path
    Map<String, List<NodeOccurrence>> occurrencesByPattern = occurrencesByPattern(occurrences);
    Set<String> suppressedTerminalPatterns = suppressedTerminalPatterns(selections, occurrencesByPattern);
    List<RowContextCandidate> candidates = candidates(occurrencesByPattern, suppressedTerminalPatterns).stream()
            .filter(candidate -> isValidCandidate(candidate, selections))
            .toList();

    if (candidates.isEmpty()) {
      if (hasRepeatedContextCandidate(occurrencesByPattern, suppressedTerminalPatterns)) {
        return problem(SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT);
      }
      return singleRow(selections, returnSelections, parameters);
    }

    int deepest = candidates.stream()
        .mapToInt(RowContextCandidate::depth)
        .max()
        .orElse(0);
    List<RowContextCandidate> deepestCandidates = candidates.stream()
        .filter(candidate -> candidate.depth() == deepest)
        .toList();

    if (deepestCandidates.size() > 1) {
      return problem(SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT);
    }

    return rowsForContext(selections, returnSelections, parameters, deepestCandidates.getFirst());
  }

  /*
   * Responsibility: Applies one executed row's
   * database-produced values to the hierarchy nodes registered
   * while mapping that row.
   */
  public void applyWriteBack(SqlRow row, DmlExecutionResult result) {
    for (DbAssignedNode node : row.getWriteBackNodes()) {
      DbSetValueWriter.write(node, result);
    }
  }

  /*
   * Responsibility: Exposes the hierarchy write-back targets
   * associated with an emitted SQL row.
   */
  public List<DbAssignedNode> registeredWriteBackNodes(SqlRow row) {
    return row.getWriteBackNodes();
  }

  /*
   * Responsibility: Builds the per-column values found while
   * walking the input hierarchy, or supplied by literals/parameters.
   *
   * xpathMapping() is either a literal value, such as
   * 'ACTIVE', a parameter value, such as ${run.id}, or a simple
   * absolute source path, such as /people/person/id.
   *
   * This method returns one ColumnSelection per mapping: the
   * mapping itself, plus the literal value or hierarchy values found
   * for it.
   */
  private List<ColumnSelection> selections(
      Hierarchy input,
      List<InputToColumnMap> mappings,
      Map<String, String> parameters)
  {
    List<ColumnSelection> selections = new ArrayList<>(mappings.size());
    for (InputToColumnMap mapping : mappings) {
      if (mapping.literal()) {
        selections.add(new ColumnSelection(
            mapping,
            List.of(new SelectedValue(
                Template.expand(mapping.xpathMapping(), parameters),
                null,
                null))));
        continue;
      }
      if (!ensureDbAssignedTargets(input, mapping)) {
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

  /*
   * Responsibility: Converts all hierarchy nodes selected by one
   * path-based column mapping into mapper value records.
   */
  private List<SelectedValue> selectedValues(Hierarchy input, InputToColumnMap mapping) {
    List<Node> nodes = selectNodes(input, mapping.xpathMapping());
    if (nodes == null) {
      return null;
    }
    List<SelectedValue> values = new ArrayList<>(nodes.size());
    for (Node node : nodes) {
      values.add(new SelectedValue(
          rawValue(node),
          valueNode(node),
          writeBackNode(mapping, node)));
    }
    return values;
  }

  /*
   * Responsibility: Resolves explicit returns mappings to hierarchy
   * nodes that can receive database-produced values.
   */
  private List<ReturnSelection> returnSelections(Hierarchy input, List<ReturnMapping> returnMappings) {
    List<ReturnSelection> selections = new ArrayList<>(returnMappings.size());
    for (ReturnMapping mapping : returnMappings) {
      List<Node> nodes = selectNodes(input, mapping.getXpath());
      if (nodes == null) {
        return null;
      }
      List<SelectedValue> values = new ArrayList<>(nodes.size());
      for (Node node : nodes) {
        if (isWritableTarget(node)) {
          values.add(new SelectedValue(
              rawValue(node),
              valueNode(node),
              new DbAssignedNode(node, mapping.getColumnName())));
        }
      }
      selections.add(new ReturnSelection(mapping, values));
    }
    return selections;
  }

  /*
   * Responsibility: Runs the mapper-owned hierarchy lookup
   * and treats unmatched paths as no selected nodes.
   */
  private List<Node> selectNodes(Hierarchy input, String xpath) {
    HierarchyPath path = parsePath(xpath);
    if (path == null) {
      return null;
    }
    for (HierarchyPath candidate : sourcePathCandidates(input, path)) {
      List<Node> nodes = input.select(candidate);
      if (!nodes.isEmpty()) {
        return nodes;
      }
    }
    return List.of();
  }

  /*
   * Responsibility: Makes mapped DML paths describe the input document rather
   * than parser-internal container nodes. A top-level collection can therefore
   * always be addressed as {users.id}: multi-property structured documents do
   * not expose their synthetic json/yaml/toml root, and a sole root array does
   * not expose its anonymous item node. Exact paths remain valid.
   */
  private List<HierarchyPath> sourcePathCandidates(
      Hierarchy input,
      HierarchyPath requested) {
    Node root = input.getRoot();
    if (root == null) {
      return List.of(requested);
    }

    List<HierarchyPath> candidates = new ArrayList<>();
    List<String> parts = requested.getPathParts();

    if (root.getName().equals(requested.getRootName())
        && hasAnonymousArrayItems(root)
        && (parts.size() == 1 || !"item".equals(parts.get(1)))) {
      List<String> expanded = new ArrayList<>(parts);
      expanded.add(1, "item");
      candidates.add(new HierarchyPath(expanded, requested.isAttribute()));
    }

    candidates.add(requested);

    if (input.getRootKind() == Hierarchy.RootKind.SYNTHETIC_OBJECT
        && !root.getName().equals(requested.getRootName())
        && hasDirectElementChild(root, requested.getRootName())) {
      List<String> expanded = new ArrayList<>(parts.size() + 1);
      expanded.add(root.getName());
      expanded.addAll(parts);
      candidates.add(new HierarchyPath(expanded, requested.isAttribute()));
    }

    return candidates;
  }

  private boolean hasAnonymousArrayItems(Node root) {
    return root.getChildren().stream()
        .anyMatch(child -> "item".equals(child.getName())
            && (child.isArrayItem() || DELIMITED_ROOTS.contains(root.getName())));
  }

  private boolean hasDirectElementChild(Node root, String name) {
    return root.getChildren().stream()
        .anyMatch(child -> !child.isAttribute() && name.equals(child.getName()));
  }

  private HierarchyPath parsePath(String sourcePath) {
    try {
      return HierarchyPath.fromSlashPath(sourcePath);
    } catch (IllegalArgumentException ex) {
      Log.error("Unsupported source path [{}]: {} Use a simple hierarchy path such as "
          + "{message.person.id}; NQ paths are not XPath, JSONPath, or YAMLPath.", sourcePath, ex.getMessage());
      return null;
    }
  }

  /*
   * Responsibility: Extracts the scalar string value from the
   * selected hierarchy node.
   */
  private String rawValue(Node node) {
    if (node == null || node.getValue() == null) {
      return "";
    }
    return node.isAttribute() ? node.getValue() : node.getValue().trim();
  }

  /*
   * Responsibility: Finds the owning element used for
   * row-context inference from a selected node.
   */
  private Node valueNode(Node node) {
    if (node == null) {
      return null;
    }
    return node.isAttribute() ? node.parent() : node;
  }

  /*
   * Responsibility: Creates a write-back registration for a
   * selected node only when that column expects a
   * database-produced value.
   */
  private DbAssignedNode writeBackNode(InputToColumnMap mapping, Node node) {
    if (!needsDbAssignment(mapping)) {
      return null;
    }
    if (mapping.columnDefinition().isUid() && !rawValue(node).isEmpty()) {
      return null;
    }
    return !isWritableTarget(node)
        ? null
        : new DbAssignedNode(node, mapping.columnDefinition().sqlName());
  }

  /*
   * Responsibility: Rejects root element write-back while allowing
   * attributes and non-root element nodes.
   */
  private boolean isWritableTarget(Node node) {
    return node != null && (node.isAttribute() || node.parent() != null);
  }

  /*
   * Responsibility: Identifies mappings whose values are
   * expected to come back from the database rather than the
   * input.
   */
  private boolean needsDbAssignment(InputToColumnMap mapping) {
    return (mapping.columnDefinition().isUid() && mapping.columnDefinition().key())
        || (mapping.columnDefinition().isDbAssigned() && !mapping.columnDefinition().key());
  }


  /*
   * Responsibility: Ensures DB-assigned column source paths
   * exist so generated values have hierarchy nodes to update.
   */
  private boolean ensureDbAssignedTargets(Hierarchy input, InputToColumnMap mapping) {
    if (!needsDbAssignment(mapping) || !mapping.xpathMapping().startsWith("/")) {
      return true;
    }
    return ensureTargetPath(input, mapping.xpathMapping(), mapping.defaultValue());
  }

  /*
   * Responsibility: Ensures explicit returns targets exist
   * before source-path selection tries to register them.
   */
  private boolean ensureReturnTargets(Hierarchy input, List<ReturnMapping> returnMappings) {
    for (ReturnMapping mapping : returnMappings) {
      if (!ensureTargetPath(input, mapping.getXpath(), null)) {
        return false;
      }
    }
    return true;
  }
  /*
   * Responsibility: Creates the concrete target path named by an
   * absolute source path when it belongs under the hierarchy root.
   */
  private boolean ensureTargetPath(Hierarchy input, String xpath, String ifnull) {
    if (!xpath.startsWith("/")) {
      return true;
    }
    HierarchyPath path = parsePath(xpath);
    if (path == null) {
      return false;
    }
    for (HierarchyPath candidate : sourcePathCandidates(input, path)) {
      HierarchyPath parent = candidate.parent();
      if (parent != null && !input.select(parent).isEmpty()) {
        input.ensureFinalTargets(candidate, ifnull);
        break;
      }
    }
    return true;
  }

  /*
   * Responsibility: Collects every selected value element and
   * ancestor as a possible participant in row-context
   * inference.
   */
  private Map<String, NodeOccurrence> occurrences(List<ColumnSelection> selections) {
    Map<String, NodeOccurrence> occurrences = new LinkedHashMap<>();
    for (ColumnSelection selection : selections) {
      for (SelectedValue value : selection.values()) {
        Node element = value.element();
        while (element != null) {
          NodeOccurrence occurrence = occurrence(element);
          occurrences.putIfAbsent(occurrence.id(), occurrence);
          element = element.parent();
        }
      }
    }
    return occurrences;
  }

  /*
   * Responsibility: Groups concrete element occurrences by their
   * unindexed element-name pattern.
   */
  private Map<String, List<NodeOccurrence>> occurrencesByPattern(
      Map<String, NodeOccurrence> occurrences) {
    Map<String, List<NodeOccurrence>> occurrencesByPattern = new LinkedHashMap<>();
    for (NodeOccurrence occurrence : occurrences.values()) {
      occurrencesByPattern
          .computeIfAbsent(occurrence.pattern(), ignored -> new ArrayList<>())
          .add(occurrence);
    }
    return occurrencesByPattern;
  }

  /*
   * Responsibility: Excludes repeated terminal value elements
   * that mirror parent repetition and should not define rows.
   */
  private Set<String> suppressedTerminalPatterns(
      List<ColumnSelection> selections,
      Map<String, List<NodeOccurrence>> occurrencesByPattern) {
    Set<String> suppressedPatterns = new LinkedHashSet<>();
    for (ColumnSelection selection : selections) {
      for (SelectedValue value : selection.values()) {
        Node element = value.element();
        if (element == null || !isTerminal(element) || element.parent() == null) {
          continue;
        }
        String elementPattern = pattern(element);
        String parentPattern = pattern(element.parent());
        if (occurrencesByPattern.getOrDefault(elementPattern, List.of()).size()
            == occurrencesByPattern.getOrDefault(parentPattern, List.of()).size()) {
          suppressedPatterns.add(elementPattern);
        }
      }
    }
    return suppressedPatterns;
  }

  /*
   * Responsibility: Reports whether a hierarchy element has no child
   * elements.
   */
  private boolean isTerminal(Node element) {
    return element.getChildren().stream().noneMatch(child -> !child.isAttribute());
  }

  /*
   * Responsibility: Builds repeated element patterns that may
   * define SQL row boundaries, deepest candidates first.
   */
  private List<RowContextCandidate> candidates(
      Map<String, List<NodeOccurrence>> occurrencesByPattern,
      Set<String> suppressedTerminalPatterns) {
    return occurrencesByPattern.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .filter(entry -> !suppressedTerminalPatterns.contains(entry.getKey()))
        .map(entry -> new RowContextCandidate(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(RowContextCandidate::depth).reversed())
        .toList();
  }

  /*
   * Responsibility: Detects whether repeated row-context
   * evidence existed before relationship validation removed it.
   */
  private boolean hasRepeatedContextCandidate(
      Map<String, List<NodeOccurrence>> occurrencesByPattern,
      Set<String> suppressedTerminalPatterns) {
    return occurrencesByPattern.entrySet().stream()
        .anyMatch(entry -> entry.getValue().size() > 1
            && !suppressedTerminalPatterns.contains(entry.getKey()));
  }

  /*
   * Responsibility: Rejects a row-context candidate when any
   * selected path value cannot be related to any candidate row.
   */
  private boolean isValidCandidate(
      RowContextCandidate candidate,
      List<ColumnSelection> selections) {
    for (ColumnSelection selection : selections) {
      if (selection.mapping().literal()) {
        continue;
      }
      for (SelectedValue value : selection.values()) {
        boolean related = candidate.contexts().stream()
            .anyMatch(context -> isRelated(selection, value, context));
        if (!related) {
          return false;
        }
      }
    }
    return true;
  }

  /*
   * Responsibility: Decides whether a selected hierarchy value
   * belongs to a candidate row context.
   */
  private boolean isRelated(
      ColumnSelection selection,
      SelectedValue value,
      NodeOccurrence context) {
    Node valueElement = value.element();
    Node contextElement = context.element();
    return valueElement == null
        || sameOrDescendant(valueElement, contextElement)
        || sameOrAncestor(valueElement, contextElement)
        || singleTerminalChildOfAncestor(selection, valueElement, contextElement);
  }

  /*
   * Responsibility: Allows a single terminal child value to
   * belong to each repeated ancestor row.
   */
  private boolean singleTerminalChildOfAncestor(
      ColumnSelection selection,
      Node valueElement,
      Node contextElement) {
    Node parent = valueElement == null ? null : valueElement.parent();
    return parent != null
        && isTerminal(valueElement)
        && sameOrAncestor(parent, contextElement)
        && selection.values().stream()
            .filter(other -> other.element() != null)
            .filter(other -> other.element().parent() == parent)
            .count() == 1;
  }

  /*
   * Responsibility: Emits one SQL row when selected values do
   * not establish a repeated hierarchy row context.
   */
  private MappingResult singleRow(
      List<ColumnSelection> selections,
      List<ReturnSelection> returnSelections,
      Map<String,String> parameters)
  {
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
    SqlRow row = new SqlRow(columns, registeredNodes);
    return new MappingResult(List.of(row));
  }

  /*
   * Responsibility: Builds one SqlRow for each repeated hierarchy
   * element and records the nodes to update after execution.
   *
   * RowContextCandidate is the repeated hierarchy path whose elements
   * each produce one SqlRow. For /people/person, it holds each
   * person element found in the input. This method builds one
   * SqlRow from that element and its child values.
   *
   * A write-back node is a hierarchy element or attribute to update
   * after the database returns a value, such as a generated id.
   * The SqlRow stores that node, so the generated id is written
   * back to the same person element.
   */
  private MappingResult rowsForContext(
      List<ColumnSelection> selections,
      List<ReturnSelection> returnSelections,
      Map<String, String> parameters,
      RowContextCandidate candidate) {
    List<SqlRow> rows = new ArrayList<>(candidate.contexts().size());
    for (NodeOccurrence context : candidate.contexts()) {
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
        SelectedValue value = matchedValues.isEmpty()
            ? missingValue(selection)
            : matchedValues.getFirst();
        addWriteBackNode(registeredNodes, value);
        columns.add(toSqlColumn(selection.mapping(), value, parameters));
      }
      if (!addContextReturnNodes(registeredNodes, returnSelections, context)) {
        return problem(SyntaxErrorType.UNRESOLVABLE_MULTI_VALUE);
      }
      SqlRow row = new SqlRow(columns, registeredNodes);
      rows.add(row);
    }
    return new MappingResult(rows);
  }

  /*
   * Responsibility: Registers explicit returns targets for a
   * single-row statement, rejecting ambiguous multi-target
   * returns.
   */
  private boolean addSingleRowReturnNodes(
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

  /*
   * Responsibility: Registers explicit returns targets that
   * fall under one inferred hierarchy row context.
   */
  private boolean addContextReturnNodes(
      List<DbAssignedNode> registeredNodes,
      List<ReturnSelection> returnSelections,
      NodeOccurrence context) {
    for (ReturnSelection selection : returnSelections) {
      List<SelectedValue> matchedValues = selection.values().stream()
          .filter(v -> v.element() == null || sameOrDescendant(v.element(), context.element()))
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

  /*
   * Responsibility: Selects the values from one column mapping
   * that belong to one inferred row context.
   */
  private List<SelectedValue> matchedValues(
      ColumnSelection selection,
      NodeOccurrence context) {
    return selection.values().stream()
        .filter(v -> isRelated(selection, v, context))
        .toList();
  }

  /*
   * Responsibility: Supplies the mapping default value when no
   * hierarchy node matched a column mapping.
   */
  private SelectedValue missingValue(ColumnSelection selection) {
    return new SelectedValue(selection.mapping().defaultValue(), null, null);
  }

  /*
   * Responsibility: Converts one selected hierarchy or default value
   * into a SQL column with its bind expression.
   */
  private SqlColumn toSqlColumn(
      InputToColumnMap mapping,
      SelectedValue value,
      Map<String, String> parameters)
  {
    return SqlColumn.from(mapping.columnDefinition(), value.rawValue(), parameters);
  }

  /*
   * Responsibility: Adds a selected value's write-back node to
   * the row registration list when one exists.
   */
  private void addWriteBackNode(List<DbAssignedNode> nodes, SelectedValue value) {
    if (value.writeBackNode() != null) {
      nodes.add(value.writeBackNode());
    }
  }

  /*
   * Responsibility: Creates a mapping result that carries a
   * row-shape problem instead of executable SQL rows.
   */
  private MappingResult problem(SyntaxErrorType status) {
    return new MappingResult(List.of(), status);
  }

  /*
   * Responsibility: Tests whether one element is the same as,
   * or nested below, another element by identity.
   */
  private boolean sameOrDescendant(Node element, Node possibleAncestor) {
    Node current = element;
    while (current != null) {
      if (current == possibleAncestor) {
        return true;
      }
      current = current.parent();
    }
    return false;
  }

  /*
   * Responsibility: Tests whether one element is the same as,
   * or above, another element by identity.
   */
  private boolean sameOrAncestor(Node element, Node possibleDescendant) {
    return sameOrDescendant(possibleDescendant, element);
  }

  /*
   * Responsibility: Builds the indexed and unindexed
   * identities used to compare hierarchy element occurrences.
   */
  private NodeOccurrence occurrence(Node element) {
    return new NodeOccurrence(elementPath(element), pattern(element), element);
  }

  /*
   * Responsibility: Builds an absolute indexed hierarchy element
   * path that distinguishes repeated sibling occurrences.
   */
  private String elementPath(Node element) {
    Node parent = element.parent();
    String segment = element.getName() + "[" + occurrenceIndex(element) + "]";
    if (parent == null) {
      return "/" + segment;
    }
    return elementPath(parent) + "/" + segment;
  }

  /*
   * Responsibility: Calculates the one-based sibling
   * occurrence index for a hierarchy element name.
   */
  private int occurrenceIndex(Node element) {
    Node parent = element.parent();
    if (parent == null) {
      return 1;
    }
    List<Node> siblings = parent.getChildren().stream()
        .filter(child -> !child.isAttribute())
        .filter(child -> Objects.equals(child.getName(), element.getName()))
        .toList();
    for (int idx = 0; idx < siblings.size(); idx++) {
      if (siblings.get(idx) == element) {
        return idx + 1;
      }
    }
    return 1;
  }

  /*
   * Responsibility: Builds an absolute unindexed hierarchy
   * element-name pattern for grouping repeated row candidates.
   */
  private String pattern(Node element) {
    Node parent = element.parent();
    if (parent == null) {
      return "/" + element.getName();
    }
    return pattern(parent) + "/" + element.getName();
  }

  // Responsibility: Groups one SQL column mapping with hierarchy
  // values selected for it.
  private record ColumnSelection(
      InputToColumnMap mapping,
      List<SelectedValue> values) {
  }

  // Responsibility: Carries one selected hierarchy value plus its
  // optional database-produced value target.
  private record SelectedValue(
      String rawValue,
      Node element,
      DbAssignedNode writeBackNode) {
  }

  // Responsibility: Groups one returns mapping with the hierarchy
  // nodes it can write back to.
  private record ReturnSelection(
      ReturnMapping mapping,
      List<SelectedValue> values) {
  }

  // Responsibility: Identifies one hierarchy element occurrence
  // considered as a possible row context.
  private record NodeOccurrence(
      String id,
      String pattern,
      Node element) {
  }

  // Responsibility: Holds repeated hierarchy occurrences that may
  // define DML row boundaries.
  private record RowContextCandidate(
      String pattern,
      List<NodeOccurrence> contexts) {
    /*
     * Responsibility: Measures candidate specificity by
     * counting path segments in the element pattern.
     */
    int depth() {
      return (int) pattern.chars().filter(ch -> ch == '/').count();
    }
  }
}
