package blater.nq.runner.sql.dml.mapping;

import blater.nq.domain.Node;
import blater.nq.runner.SyntaxErrorType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Infers the repeated hierarchy element that defines one SQL row. */
final class RowContextResolver {
  private RowContextResolver() {
  }

  static RowContextResolution resolve(List<ColumnSelection> selections) {
    Map<String, NodeOccurrence> occurrences = occurrences(selections);
    Map<String, List<NodeOccurrence>> occurrencesByPattern = occurrencesByPattern(occurrences);
    Set<String> suppressed = suppressedTerminalPatterns(selections, occurrencesByPattern);
    List<RowContextCandidate> candidates = candidates(occurrencesByPattern, suppressed).stream()
        .filter(candidate -> isValidCandidate(candidate, selections))
        .toList();
    if (candidates.isEmpty()) {
      SyntaxErrorType problem = hasRepeatedCandidate(occurrencesByPattern, suppressed)
          ? SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT
          : null;
      return new RowContextResolution(null, problem);
    }
    int deepest = candidates.stream().mapToInt(RowContextCandidate::depth).max().orElse(0);
    List<RowContextCandidate> deepestCandidates = candidates.stream()
        .filter(candidate -> candidate.depth() == deepest)
        .toList();
    return deepestCandidates.size() > 1
        ? new RowContextResolution(null, SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT)
        : new RowContextResolution(deepestCandidates.getFirst(), null);
  }

  private static Map<String, NodeOccurrence> occurrences(List<ColumnSelection> selections) {
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

  private static Map<String, List<NodeOccurrence>> occurrencesByPattern(
      Map<String, NodeOccurrence> occurrences) {

    Map<String, List<NodeOccurrence>> grouped = new LinkedHashMap<>();
    for (NodeOccurrence occurrence : occurrences.values()) {
      grouped.computeIfAbsent(occurrence.pattern(), ignored -> new ArrayList<>()).add(occurrence);
    }
    return grouped;
  }

  private static Set<String> suppressedTerminalPatterns(
      List<ColumnSelection> selections,
      Map<String, List<NodeOccurrence>> occurrencesByPattern) {

    Set<String> suppressed = new LinkedHashSet<>();
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
          suppressed.add(elementPattern);
        }
      }
    }
    return suppressed;
  }

  private static boolean isTerminal(Node element) {
    return element.getChildren().stream().noneMatch(child -> !child.isAttribute());
  }

  private static List<RowContextCandidate> candidates(
      Map<String, List<NodeOccurrence>> occurrencesByPattern,
      Set<String> suppressed) {

    return occurrencesByPattern.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .filter(entry -> !suppressed.contains(entry.getKey()))
        .map(entry -> new RowContextCandidate(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(RowContextCandidate::depth).reversed())
        .toList();
  }

  private static boolean hasRepeatedCandidate(
      Map<String, List<NodeOccurrence>> occurrencesByPattern,
      Set<String> suppressed) {

    return occurrencesByPattern.entrySet().stream()
        .anyMatch(entry -> entry.getValue().size() > 1 && !suppressed.contains(entry.getKey()));
  }

  private static boolean isValidCandidate(
      RowContextCandidate candidate,
      List<ColumnSelection> selections) {

    for (ColumnSelection selection : selections) {
      if (selection.mapping().literal()) {
        continue;
      }
      for (SelectedValue value : selection.values()) {
        if (candidate.contexts().stream().noneMatch(context -> isRelated(selection, value, context))) {
          return false;
        }
      }
    }
    return true;
  }

  static boolean isRelated(
      ColumnSelection selection,
      SelectedValue value,
      NodeOccurrence context) {

    Node valueElement = value.element();
    Node contextElement = context.element();
    return valueElement == null
        || sameOrDescendant(valueElement, contextElement)
        || sameOrDescendant(contextElement, valueElement)
        || singleTerminalChildOfAncestor(selection, valueElement, contextElement);
  }

  private static boolean singleTerminalChildOfAncestor(
      ColumnSelection selection,
      Node valueElement,
      Node contextElement) {

    Node parent = valueElement == null ? null : valueElement.parent();
    return parent != null
        && isTerminal(valueElement)
        && sameOrDescendant(contextElement, parent)
        && selection.values().stream()
            .filter(other -> other.element() != null)
            .filter(other -> other.element().parent() == parent)
            .count() == 1;
  }

  static boolean sameOrDescendant(Node element, Node possibleAncestor) {
    Node current = element;
    while (current != null) {
      if (current == possibleAncestor) {
        return true;
      }
      current = current.parent();
    }
    return false;
  }

  private static NodeOccurrence occurrence(Node element) {
    return new NodeOccurrence(elementPath(element), pattern(element), element);
  }

  private static String elementPath(Node element) {
    Node parent = element.parent();
    String segment = element.getName() + "[" + occurrenceIndex(element) + "]";
    return parent == null ? "/" + segment : elementPath(parent) + "/" + segment;
  }

  private static int occurrenceIndex(Node element) {
    Node parent = element.parent();
    if (parent == null) {
      return 1;
    }
    List<Node> siblings = parent.getChildren().stream()
        .filter(child -> !child.isAttribute())
        .filter(child -> Objects.equals(child.getName(), element.getName()))
        .toList();
    for (int index = 0; index < siblings.size(); index++) {
      if (siblings.get(index) == element) {
        return index + 1;
      }
    }
    return 1;
  }

  private static String pattern(Node element) {
    Node parent = element.parent();
    return parent == null ? "/" + element.getName() : pattern(parent) + "/" + element.getName();
  }
}
