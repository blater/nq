package blater.nql.runner.inference;

import blater.nql.domain.HierarchyPath;
import blater.nql.parser.script.QueryShape;
import blater.nql.parser.script.SelectBlueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Chooses stable identity expressions for paths whose owning relations are known. */
final class InferenceIdentityResolver {
  private final DatabaseStructure structure;

  InferenceIdentityResolver(DatabaseStructure structure) {
    this.structure = structure;
  }

  Map<Integer, IdentityDecision> decisionsFor(
      SelectBlueprint blueprint,
      HierarchyPath path,
      InferenceRelationBindings bindings) {
    Map<Integer, IdentityDecision> decisions = new LinkedHashMap<>();
    for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
      SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
      if (!branch.mapsPath(path)) {
        continue;
      }
      IdentityDecision decision = identityFor(
          path, branchIndex, blueprint.branches().size(), branch,
          bindings.occurrences(branchIndex), bindings.owners(branchIndex));
      if (decision != null) {
        decisions.put(branchIndex, decision);
      }
    }
    return decisions;
  }

  private IdentityDecision identityFor(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      SelectBlueprint.Branch branch,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      Map<HierarchyPath, InferenceRelationBindings.RelationOccurrence> owners) {
    QueryShape shape = branch.queryShape();
    if (shape.characteristics().distinct() == QueryShape.TruthValue.YES) {
      return null;
    }

    InferenceRelationBindings.RelationOccurrence owner = owners.get(path);
    InferenceRelationBindings.RelationOccurrence parent = owners.get(path.parent());
    if (shape.grouping() instanceof QueryShape.KnownGrouping grouping) {
      return groupedIdentity(
          path, branchIndex, branchCount, branch, grouping, occurrences, owner, parent);
    }
    if (shape.grouping() instanceof QueryShape.UnsupportedGrouping
        || shape.characteristics().containsAggregate() != QueryShape.TruthValue.NO
        || owner == null) {
      return null;
    }
    DatabaseStructure.CandidateKey key = owner.relation().preferredKey().orElse(null);
    if (key == null || key.columns().isEmpty()) {
      return null;
    }
    return metadataIdentity(path, branchIndex, branchCount, branch, occurrences, owner, parent, key);
  }

  private IdentityDecision groupedIdentity(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      SelectBlueprint.Branch branch,
      QueryShape.KnownGrouping grouping,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      InferenceRelationBindings.RelationOccurrence owner,
      InferenceRelationBindings.RelationOccurrence parent) {
    List<InferenceRelationBindings.ResolvedColumn> groupedColumns = grouping.expressions().stream()
        .map(expression -> InferenceRelationBindings.resolveColumn(
            expression.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource()))
        .flatMap(Optional::stream)
        .toList();
    DatabaseStructure.CandidateKey key = eligibleGroupedKey(owner, groupedColumns);
    if (key != null) {
      return metadataIdentity(path, branchIndex, branchCount, branch, occurrences, owner, parent, key);
    }
    List<String> summary = summaryIdentity(path, branch, grouping, occurrences);
    if (summary.isEmpty()) {
      return null;
    }
    return new IdentityDecision(
        summary, owner == null ? null : owner.relation(),
        InferenceUsageFormatter.grouping(path, branchIndex, branchCount, summary));
  }

  private IdentityDecision metadataIdentity(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      SelectBlueprint.Branch branch,
      List<InferenceRelationBindings.RelationOccurrence> occurrences,
      InferenceRelationBindings.RelationOccurrence owner,
      InferenceRelationBindings.RelationOccurrence parent,
      DatabaseStructure.CandidateKey key) {
    return new IdentityDecision(
        keyExpressions(owner, key, branch, occurrences),
        owner.relation(),
        InferenceUsageFormatter.metadata(
            path, branchIndex, branchCount, owner, key, parent,
            InferenceOwnerScorer.relationshipsBetween(parent, owner, structure)));
  }

  private static DatabaseStructure.CandidateKey eligibleGroupedKey(
      InferenceRelationBindings.RelationOccurrence owner,
      List<InferenceRelationBindings.ResolvedColumn> groupedColumns) {
    if (owner == null) {
      return null;
    }
    return owner.relation().candidateKeys().stream()
        .filter(key -> key.columns().stream().allMatch(column -> groupedColumns.stream().anyMatch(grouped ->
            grouped.occurrence().equals(owner) && grouped.column().equalsIgnoreCase(column))))
        .findFirst()
        .orElse(null);
  }

  private static List<String> summaryIdentity(
      HierarchyPath path,
      SelectBlueprint.Branch branch,
      QueryShape.KnownGrouping grouping,
      List<InferenceRelationBindings.RelationOccurrence> occurrences) {
    List<SelectBlueprint.SelectItem> items = InferenceRelationBindings.itemsAt(path, branch.items());
    if (items.stream().noneMatch(item ->
        item.expressionFacts().aggregate() == QueryShape.TruthValue.YES)
        || items.stream().anyMatch(item ->
        item.expressionFacts().aggregate() == QueryShape.TruthValue.UNKNOWN)) {
      return List.of();
    }
    for (SelectBlueprint.SelectItem item : items) {
      if (!isGroupedItem(item, branch, grouping, occurrences)) {
        return List.of();
      }
    }
    return grouping.expressions().stream().map(QueryShape.ExpressionFacts::originalSql).toList();
  }

  private static boolean isGroupedItem(
      SelectBlueprint.SelectItem item,
      SelectBlueprint.Branch branch,
      QueryShape.KnownGrouping grouping,
      List<InferenceRelationBindings.RelationOccurrence> occurrences) {
    QueryShape.ExpressionFacts facts = item.expressionFacts();
    if (facts.aggregate() == QueryShape.TruthValue.YES) {
      return true;
    }
    Optional<InferenceRelationBindings.ResolvedColumn> mapped = InferenceRelationBindings.resolveColumn(
        facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource());
    if (facts.directColumn().isPresent() && mapped.isEmpty()) {
      return false;
    }
    if (grouping.expressions().stream().anyMatch(facts::structurallyEquals)) {
      return true;
    }
    return mapped.isPresent() && grouping.expressions().stream()
        .map(expression -> InferenceRelationBindings.resolveColumn(
            expression.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource()))
        .flatMap(Optional::stream)
        .anyMatch(mapped.get()::equals);
  }

  private static List<String> keyExpressions(
      InferenceRelationBindings.RelationOccurrence occurrence,
      DatabaseStructure.CandidateKey key,
      SelectBlueprint.Branch branch,
      List<InferenceRelationBindings.RelationOccurrence> occurrences) {
    List<QueryShape.ExpressionFacts> available = new ArrayList<>();
    branch.items().stream().map(SelectBlueprint.SelectItem::expressionFacts).forEach(available::add);
    if (branch.queryShape().grouping() instanceof QueryShape.KnownGrouping grouping) {
      available.addAll(grouping.expressions());
    }
    return key.columns().stream()
        .map(column -> available.stream()
            .filter(facts -> InferenceRelationBindings.resolveColumn(
                facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource())
                .filter(resolved -> resolved.occurrence().equals(occurrence)
                    && resolved.column().equalsIgnoreCase(column))
                .isPresent())
            .map(QueryShape.ExpressionFacts::originalSql)
            .findFirst()
            .orElseGet(() -> occurrence.sqlAlias() + "." + InferenceUsageFormatter.renderColumn(column)))
        .toList();
  }
  record IdentityDecision(
      List<String> expressions,
      DatabaseStructure.Relation owner,
      String usage) {
    IdentityDecision {
      expressions = List.copyOf(expressions);
    }
  }
}
