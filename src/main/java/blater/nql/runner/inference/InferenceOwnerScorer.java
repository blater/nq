package blater.nql.runner.inference;

import blater.nql.domain.HierarchyPath;
import blater.nql.parser.script.QueryShape;
import blater.nql.parser.script.SelectBlueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Scores candidate database relations for ownership of an output path. */
final class InferenceOwnerScorer {
  private InferenceOwnerScorer() {
  }

  static List<ScoredOccurrence> scoreCandidates(
      HierarchyPath path,
      SelectBlueprint.Branch branch,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      SelectBlueprint.StructureKey explicitKey,
      InferenceRelationBindings.RelationOccurrence parent,
      DatabaseStructure structure) {
    List<BoundItem> boundItems = boundItemsFor(path, branch, occurrences, explicitKey);
    Map<InferenceRelationBindings.RelationOccurrence, Integer> scores =
        initialScores(boundItems, occurrences);
    for (InferenceRelationBindings.RelationOccurrence occurrence
        : new ArrayList<>(scores.keySet())) {
      scores.computeIfPresent(occurrence, (ignored, score) -> score
          + pathMatchScore(path, occurrence)
          + relationshipScore(parent, occurrence, structure)
          + keyEvidenceScore(occurrence));
    }
    return scores.entrySet().stream()
        .map(entry -> new ScoredOccurrence(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(ScoredOccurrence::score).reversed()
            .thenComparing(item -> item.occurrence().alias().text(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  static List<DatabaseStructure.Relationship> relationshipsBetween(
      InferenceRelationBindings.RelationOccurrence left,
      InferenceRelationBindings.RelationOccurrence right,
      DatabaseStructure structure) {
    if (left == null || right == null) {
      return List.of();
    }
    return structure.relationships().stream().filter(relationship ->
        relationship.source().equals(left.relation().id())
            && relationship.target().equals(right.relation().id())
            || relationship.source().equals(right.relation().id())
            && relationship.target().equals(left.relation().id()))
        .toList();
  }

  private static List<BoundItem> boundItemsFor(
      HierarchyPath path,
      SelectBlueprint.Branch branch,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      SelectBlueprint.StructureKey explicitKey) {
    List<BoundItem> result = new ArrayList<>();
    for (SelectBlueprint.SelectItem item : InferenceRelationBindings.itemsAt(path, branch.items())) {
      addBoundItem(result, occurrences, branch, item, item.outputPath().getTerminalNodeName());
    }
    if (explicitKey != null
        && explicitKey.keyExpressions() instanceof SelectBlueprint.CommonKeyExpressions common) {
      for (QueryShape.ExpressionFacts facts : common.expressionFacts()) {
        addBoundExpression(
            result, occurrences, branch, facts, path.getTerminalNodeName());
      }
    }
    return result;
  }

  private static void addBoundItem(
      List<BoundItem> result,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      SelectBlueprint.Branch branch,
      SelectBlueprint.SelectItem item,
      String outputTerminal) {
    addBoundExpression(result, occurrences, branch, item.expressionFacts(), outputTerminal);
  }

  private static void addBoundExpression(
      List<BoundItem> result,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      SelectBlueprint.Branch branch,
      QueryShape.ExpressionFacts facts,
      String outputTerminal) {
    InferenceRelationBindings.resolveColumn(
        facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource())
        .ifPresent(column -> result.add(
            new BoundItem(column.occurrence(), column.column(), outputTerminal)));
  }

  private static Map<InferenceRelationBindings.RelationOccurrence, Integer> initialScores(
      List<BoundItem> boundItems,
      List<InferenceRelationBindings.RelationOccurrence> occurrences) {
    Map<InferenceRelationBindings.RelationOccurrence, Integer> scores = new LinkedHashMap<>();
    for (BoundItem item : boundItems) {
      scores.merge(item.occurrence(), itemScore(item), Integer::sum);
    }
    if (scores.isEmpty() && occurrences.size() == 1) {
      scores.put(occurrences.getFirst(), 1);
    }
    return scores;
  }

  private static int itemScore(BoundItem item) {
    int score = 10;
    DatabaseStructure.CandidateKey key = item.occurrence().relation().preferredKey().orElse(null);
    if (key != null && key.columns().stream().anyMatch(item.column()::equalsIgnoreCase)) {
      score += 50;
    }
    return "id".equalsIgnoreCase(item.outputTerminal()) ? score + 25 : score;
  }

  private static int pathMatchScore(
      HierarchyPath path, InferenceRelationBindings.RelationOccurrence occurrence) {
    String terminal = DatabaseStructure.normalize(path.getTerminalNodeName());
    if (terminal.equals(DatabaseStructure.normalize(occurrence.alias().text()))) {
      return 100;
    }
    return terminal.equals(DatabaseStructure.normalize(occurrence.relation().name())) ? 80 : 0;
  }

  private static int relationshipScore(
      InferenceRelationBindings.RelationOccurrence parent,
      InferenceRelationBindings.RelationOccurrence occurrence,
      DatabaseStructure structure) {
    return relationshipsBetween(parent, occurrence, structure).isEmpty() ? 0 : 200;
  }

  private static int keyEvidenceScore(InferenceRelationBindings.RelationOccurrence occurrence) {
    return occurrence.relation().preferredKey()
        .map(key -> 10 - Math.min(9, key.evidence().rank() * 2))
        .orElse(0);
  }

  private record BoundItem(
      InferenceRelationBindings.RelationOccurrence occurrence,
      String column,
      String outputTerminal) {
  }

  record ScoredOccurrence(
      InferenceRelationBindings.RelationOccurrence occurrence, int score) {
  }
}
