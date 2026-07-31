package blater.nq.runner.inference;

import blater.nq.domain.HierarchyPath;
import blater.nq.domain.RepetitionPlacement;
import blater.nq.parser.script.QueryShape;
import blater.nq.parser.script.SelectBlueprint;
import blater.nq.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Applies a cached database graph to one parsed DQL SELECT. */
public final class KeyInferencePlanner {

  public CompiledSelect compile(SelectBlueprint blueprint, DatabaseStructure structure) {
    if (blueprint == null) {
      throw new IllegalArgumentException("A SELECT blueprint is required for key inference.");
    }

    Set<HierarchyPath> explicitPaths = new LinkedHashSet<>();
    blueprint.explicitKeys().forEach(key -> explicitPaths.add(key.path()));
    Set<HierarchyPath> planningPaths = new LinkedHashSet<>(blueprint.objectPaths());
    planningPaths.addAll(explicitPaths);
    List<HierarchyPath> paths = planningPaths.stream()
        .sorted(Comparator.comparingInt(path -> path.getPathParts().size()))
        .toList();

    Map<Integer, List<RelationOccurrence>> occurrencesByBranch = bindOccurrences(blueprint, structure);
    Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch = bindOwners(
        blueprint, structure, paths, occurrencesByBranch);

    List<SelectBlueprint.StructureKey> inferred = new ArrayList<>();
    List<String> inferenceUsage = new ArrayList<>();
    Set<HierarchyPath> effectiveKeyPaths = new LinkedHashSet<>(explicitPaths);

    for (HierarchyPath path : paths) {
      if (explicitPaths.contains(path)) continue;

      Map<Integer, IdentityDecision> decisions = new LinkedHashMap<>();
      for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
        SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
        if (!branch.mapsPath(path)) continue;
        IdentityDecision decision = identityFor(
            path,
            branchIndex,
            blueprint.branches().size(),
            branch,
            occurrencesByBranch.getOrDefault(branchIndex, List.of()),
            ownersByBranch.getOrDefault(branchIndex, Map.of()),
            structure);
        if (decision != null) decisions.put(branchIndex, decision);
      }

      long mappedBranches = blueprint.branches().stream().filter(branch -> branch.mapsPath(path)).count();
      if (decisions.isEmpty()) continue;
      if (decisions.size() != mappedBranches) {
        Log.warn(
            "Could not infer a key for every hierarchy-union branch at output path [{}]; preserving row-first behavior for that path.",
            dotted(path));
        continue;
      }
      int arity = decisions.values().iterator().next().expressions().size();
      if (decisions.values().stream().anyMatch(decision -> decision.expressions().size() != arity)) {
        Log.warn(
            "Could not infer a compatible hierarchy-union key for output path [{}]; preserving row-first behavior for that path.",
            dotted(path));
        continue;
      }

      Map<Integer, RepetitionPlacement> placements = new LinkedHashMap<>();
      decisions.forEach((branchIndex, decision) -> placements.put(
          branchIndex,
          OutputPlacementPolicy.inferred(path, decision.owner(), effectiveKeyPaths)));
      RepetitionPlacement placement = placements.values().iterator().next();
      if (placements.values().stream().anyMatch(candidate -> !candidate.equals(placement))) {
        Log.warn(
            "Could not infer compatible hierarchy-union placement for output path [{}]; preserving row-first behavior for that path.",
            dotted(path));
        continue;
      }

      if (blueprint.branches().size() == 1) {
        inferred.add(SelectBlueprint.StructureKey.inferred(
            path, placement, decisions.values().iterator().next().expressions()));
      } else {
        Map<Integer, List<String>> branchExpressions = new LinkedHashMap<>();
        decisions.forEach((branch, decision) -> branchExpressions.put(branch, decision.expressions()));
        inferred.add(SelectBlueprint.StructureKey.inferredBranches(path, placement, branchExpressions));
      }
      effectiveKeyPaths.add(path);
      decisions.values().stream().map(IdentityDecision::usage).forEach(inferenceUsage::add);
    }

    SelectBlueprint.Compiled compiled = blueprint.compile(inferred);
    logInferenceUsage(inferenceUsage);
    return new CompiledSelect(compiled.sql(), compiled.plan());
  }

  public static QueryShape.ReferencedRelations referencedRelations(SelectBlueprint blueprint) {
    if (blueprint == null) return QueryShape.ReferencedRelations.none();
    return QueryShape.referencedRelations(
        blueprint.branches().stream().map(SelectBlueprint.Branch::queryShape).toList());
  }

  private static Map<Integer, List<RelationOccurrence>> bindOccurrences(
      SelectBlueprint blueprint,
      DatabaseStructure structure) {
    Map<Integer, List<RelationOccurrence>> result = new LinkedHashMap<>();
    for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
      SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
      List<RelationOccurrence> occurrences = new ArrayList<>();
      for (QueryShape.BaseRelation reference : branch.queryShape().baseRelations()) {
        resolveRelation(structure, reference.qualifiedName()).ifPresent(relation -> occurrences.add(
            new RelationOccurrence(reference.effectiveAlias(), reference.effectiveAlias().sql(), relation)));
      }
      result.put(branchIndex, List.copyOf(occurrences));
    }
    return result;
  }

  private static Map<Integer, Map<HierarchyPath, RelationOccurrence>> bindOwners(
      SelectBlueprint blueprint,
      DatabaseStructure structure,
      List<HierarchyPath> paths,
      Map<Integer, List<RelationOccurrence>> occurrencesByBranch) {
    Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch = new HashMap<>();
    for (HierarchyPath path : paths) {
      for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
        SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
        if (!branch.mapsPath(path)) continue;
        List<RelationOccurrence> occurrences = occurrencesByBranch.getOrDefault(branchIndex, List.of());
        SelectBlueprint.StructureKey explicitKey = blueprint.explicitKeys().stream()
            .filter(key -> key.path().equals(path))
            .findFirst()
            .orElse(null);
        List<BoundItem> boundItems = boundItemsFor(path, branch, occurrences, explicitKey);
        Map<HierarchyPath, RelationOccurrence> owners = ownersByBranch.computeIfAbsent(
            branchIndex, ignored -> new HashMap<>());
        RelationOccurrence parent = owners.get(path.parent());
        List<ScoredOccurrence> candidates = scoreCandidates(path, boundItems, occurrences, parent, structure);
        if (candidates.isEmpty()) continue;
        ScoredOccurrence winner = candidates.getFirst();
        if (candidates.size() > 1 && winner.score() == candidates.get(1).score()) {
          Log.warn(
              "Ambiguous key inference for output path [{}] branch [{}]; selected [{}] over [{}]. Possible data loss; add an explicit structure key or use --no-key-inference.",
              dotted(path), branchIndex + 1, winner.occurrence().alias().text(),
              candidates.get(1).occurrence().alias().text());
        }
        owners.put(path, winner.occurrence());
      }
    }
    return ownersByBranch;
  }

  private static IdentityDecision identityFor(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      SelectBlueprint.Branch branch,
      List<RelationOccurrence> occurrences,
      Map<HierarchyPath, RelationOccurrence> owners,
      DatabaseStructure structure) {
    QueryShape shape = branch.queryShape();
    if (shape.characteristics().distinct() == QueryShape.TruthValue.YES) return null;

    RelationOccurrence owner = owners.get(path);
    RelationOccurrence parent = owners.get(path.parent());
    if (shape.grouping() instanceof QueryShape.KnownGrouping grouping) {
      List<ResolvedColumn> groupedColumns = grouping.expressions().stream()
          .map(expression -> resolveColumn(expression.directColumn(), occurrences, shape.hasUnsupportedSource()))
          .filter(Optional::isPresent)
          .map(Optional::get)
          .toList();
      DatabaseStructure.CandidateKey key = eligibleGroupedKey(owner, groupedColumns);
      if (key != null) {
        List<String> expressions = keyExpressions(owner, key, branch, occurrences);
        return new IdentityDecision(
            expressions,
            owner.relation(),
            formatMetadataUsage(
                path, branchIndex, branchCount, owner, key, parent,
                relationshipsBetween(parent, owner, structure)));
      }
      List<String> summary = summaryIdentity(path, branch, grouping, occurrences);
      if (!summary.isEmpty()) {
        return new IdentityDecision(
            summary,
            owner == null ? null : owner.relation(),
            formatGroupingUsage(path, branchIndex, branchCount, summary));
      }
      return null;
    }
    if (shape.grouping() instanceof QueryShape.UnsupportedGrouping) return null;
    if (shape.characteristics().containsAggregate() != QueryShape.TruthValue.NO) return null;
    if (owner == null) return null;
    DatabaseStructure.CandidateKey key = owner.relation().preferredKey().orElse(null);
    if (key == null || key.columns().isEmpty()) return null;
    return new IdentityDecision(
        keyExpressions(owner, key, branch, occurrences),
        owner.relation(),
        formatMetadataUsage(
            path, branchIndex, branchCount, owner, key, parent,
            relationshipsBetween(parent, owner, structure)));
  }

  private static DatabaseStructure.CandidateKey eligibleGroupedKey(
      RelationOccurrence owner,
      List<ResolvedColumn> groupedColumns) {
    if (owner == null) return null;
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
      List<RelationOccurrence> occurrences) {
    List<SelectBlueprint.SelectItem> items = itemsAt(path, branch.items());
    if (items.stream().noneMatch(item ->
        item.expressionFacts().aggregate() == QueryShape.TruthValue.YES)) return List.of();
    if (items.stream().anyMatch(item ->
        item.expressionFacts().aggregate() == QueryShape.TruthValue.UNKNOWN)) return List.of();

    for (SelectBlueprint.SelectItem item : items) {
      QueryShape.ExpressionFacts facts = item.expressionFacts();
      if (facts.aggregate() == QueryShape.TruthValue.YES) continue;
      if (facts.directColumn().isPresent()
          && resolveColumn(facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource()).isEmpty()) {
        return List.of();
      }
      boolean grouped = grouping.expressions().stream().anyMatch(facts::structurallyEquals);
      if (!grouped) {
        Optional<ResolvedColumn> mapped = resolveColumn(
            facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource());
        grouped = mapped.isPresent() && grouping.expressions().stream()
            .map(expression -> resolveColumn(
                expression.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .anyMatch(mapped.get()::equals);
      }
      if (!grouped) return List.of();
    }
    return grouping.expressions().stream().map(QueryShape.ExpressionFacts::originalSql).toList();
  }

  private static List<SelectBlueprint.SelectItem> itemsAt(
      HierarchyPath path,
      List<SelectBlueprint.SelectItem> items) {
    return items.stream()
        .filter(item -> item.outputPath() != null && path.equals(item.outputPath().parent()))
        .toList();
  }

  private static List<String> keyExpressions(
      RelationOccurrence occurrence,
      DatabaseStructure.CandidateKey key,
      SelectBlueprint.Branch branch,
      List<RelationOccurrence> occurrences) {
    List<QueryShape.ExpressionFacts> available = new ArrayList<>();
    branch.items().stream().map(SelectBlueprint.SelectItem::expressionFacts).forEach(available::add);
    if (branch.queryShape().grouping() instanceof QueryShape.KnownGrouping grouping) {
      available.addAll(grouping.expressions());
    }
    return key.columns().stream()
        .map(column -> available.stream()
            .filter(facts -> resolveColumn(
                facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource())
                .filter(resolved -> resolved.occurrence().equals(occurrence)
                    && resolved.column().equalsIgnoreCase(column))
                .isPresent())
            .map(QueryShape.ExpressionFacts::originalSql)
            .findFirst()
            .orElseGet(() -> occurrence.sqlAlias() + "." + renderColumn(column)))
        .toList();
  }

  private static String renderColumn(String column) {
    if (simpleIdentifier(column)) return column;
    return "\"" + column.replace("\"", "\"\"") + "\"";
  }

  private static boolean simpleIdentifier(String value) {
    if (value == null || value.isEmpty()) return false;
    char first = value.charAt(0);
    if (!Character.isLetter(first) && first != '_' && first != '$') return false;
    for (int index = 1; index < value.length(); index++) {
      char character = value.charAt(index);
      if (!Character.isLetterOrDigit(character) && character != '_' && character != '$') return false;
    }
    return true;
  }

  private static Optional<ResolvedColumn> resolveColumn(
      Optional<QueryShape.DirectColumnReference> reference,
      List<RelationOccurrence> occurrences,
      boolean hasUnsupportedSource) {
    if (reference.isEmpty()) return Optional.empty();
    QueryShape.DirectColumnReference column = reference.get();
    if (column.qualifier().isPresent()) {
      QueryShape.SqlIdentifier qualifier = column.qualifier().get();
      List<RelationOccurrence> matches = occurrences.stream()
          .filter(occurrence -> qualifierMatches(qualifier, occurrence.alias()))
          .filter(occurrence -> columnIn(occurrence.relation(), column.column()).isPresent())
          .toList();
      if (matches.size() != 1) return Optional.empty();
      String name = columnIn(matches.getFirst().relation(), column.column()).orElseThrow().name();
      return Optional.of(new ResolvedColumn(matches.getFirst(), name));
    }
    if (hasUnsupportedSource) return Optional.empty();
    List<ResolvedColumn> matches = occurrences.stream()
        .map(occurrence -> columnIn(occurrence.relation(), column.column())
            .map(found -> new ResolvedColumn(occurrence, found.name())))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static boolean qualifierMatches(
      QueryShape.SqlIdentifier qualifier,
      QueryShape.IdentifierPart alias) {
    return qualifier.parts().size() == 1 && qualifier.parts().getFirst().matches(alias.text());
  }

  private static Optional<DatabaseStructure.Column> columnIn(
      DatabaseStructure.Relation relation,
      QueryShape.IdentifierPart column) {
    return relation.columns().stream().filter(candidate -> column.matches(candidate.name())).findFirst();
  }

  private static Optional<DatabaseStructure.Relation> resolveRelation(
      DatabaseStructure structure,
      QueryShape.SqlIdentifier identifier) {
    List<DatabaseStructure.Relation> matches = structure.relations().stream()
        .filter(relation -> relationMatches(relation.id(), identifier.parts()))
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static boolean relationMatches(
      DatabaseStructure.RelationId id,
      List<QueryShape.IdentifierPart> parts) {
    if (parts.size() == 1) return parts.getFirst().matches(id.name());
    if (parts.size() == 2) {
      return id.schema() != null && parts.get(0).matches(id.schema()) && parts.get(1).matches(id.name());
    }
    if (parts.size() == 3) {
      return id.catalog() != null && id.schema() != null
          && parts.get(0).matches(id.catalog())
          && parts.get(1).matches(id.schema())
          && parts.get(2).matches(id.name());
    }
    return false;
  }

  private static List<BoundItem> boundItemsFor(
      HierarchyPath path,
      SelectBlueprint.Branch branch,
      List<RelationOccurrence> occurrences,
      SelectBlueprint.StructureKey explicitKey) {
    List<BoundItem> result = new ArrayList<>();
    for (SelectBlueprint.SelectItem item : itemsAt(path, branch.items())) {
      resolveColumn(
          item.expressionFacts().directColumn(), occurrences, branch.queryShape().hasUnsupportedSource())
          .ifPresent(column -> result.add(new BoundItem(
              column.occurrence(), column.column(), item.outputPath().getTerminalNodeName())));
    }
    if (explicitKey != null
        && explicitKey.keyExpressions() instanceof SelectBlueprint.CommonKeyExpressions common) {
      for (QueryShape.ExpressionFacts facts : common.expressionFacts()) {
        resolveColumn(facts.directColumn(), occurrences, branch.queryShape().hasUnsupportedSource())
            .ifPresent(column -> result.add(new BoundItem(
                column.occurrence(), column.column(), path.getTerminalNodeName())));
      }
    }
    return result;
  }

  private static List<ScoredOccurrence> scoreCandidates(
      HierarchyPath path,
      List<BoundItem> boundItems,
      List<RelationOccurrence> occurrences,
      RelationOccurrence parent,
      DatabaseStructure structure) {
    Map<RelationOccurrence, Integer> scores = new LinkedHashMap<>();
    for (BoundItem item : boundItems) {
      scores.putIfAbsent(item.occurrence(), 0);
      int score = scores.get(item.occurrence()) + 10;
      DatabaseStructure.CandidateKey key = item.occurrence().relation().preferredKey().orElse(null);
      if (key != null && key.columns().stream().anyMatch(item.column()::equalsIgnoreCase)) score += 50;
      if ("id".equalsIgnoreCase(item.outputTerminal())) score += 25;
      scores.put(item.occurrence(), score);
    }
    if (scores.isEmpty() && occurrences.size() == 1) scores.put(occurrences.getFirst(), 1);
    for (RelationOccurrence occurrence : new ArrayList<>(scores.keySet())) {
      int score = scores.get(occurrence);
      String terminal = DatabaseStructure.normalize(path.getTerminalNodeName());
      if (terminal.equals(DatabaseStructure.normalize(occurrence.alias().text()))) score += 100;
      if (terminal.equals(DatabaseStructure.normalize(occurrence.relation().name()))) score += 80;
      if (parent != null && related(parent, occurrence, structure)) score += 200;
      DatabaseStructure.CandidateKey key = occurrence.relation().preferredKey().orElse(null);
      if (key != null) score += 10 - Math.min(9, key.evidence().rank() * 2);
      scores.put(occurrence, score);
    }
    return scores.entrySet().stream()
        .map(entry -> new ScoredOccurrence(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(ScoredOccurrence::score).reversed()
            .thenComparing(item -> item.occurrence().alias().text(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private static boolean related(
      RelationOccurrence left,
      RelationOccurrence right,
      DatabaseStructure structure) {
    return !relationshipsBetween(left, right, structure).isEmpty();
  }

  private static List<DatabaseStructure.Relationship> relationshipsBetween(
      RelationOccurrence left,
      RelationOccurrence right,
      DatabaseStructure structure) {
    if (left == null || right == null) return List.of();
    return structure.relationships().stream().filter(relationship ->
        relationship.source().equals(left.relation().id()) && relationship.target().equals(right.relation().id())
            || relationship.source().equals(right.relation().id()) && relationship.target().equals(left.relation().id()))
        .toList();
  }

  private static String formatMetadataUsage(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      RelationOccurrence occurrence,
      DatabaseStructure.CandidateKey key,
      RelationOccurrence parent,
      List<DatabaseStructure.Relationship> relationships) {
    StringBuilder result = new StringBuilder("  {")
        .append(dotted(path)).append("}")
        .append(branchLabel(branchIndex, branchCount))
        .append(" -> ").append(occurrence.alias().text())
        .append(" [").append(occurrence.relation().id().qualifiedName()).append("]")
        .append(", key (").append(String.join(", ", key.columns())).append(")")
        .append(" [").append(key.evidence()).append("]");
    if (parent != null && !relationships.isEmpty()) {
      result.append(", parent ").append(parent.alias().text()).append(" via ");
      for (int index = 0; index < relationships.size(); index++) {
        if (index > 0) result.append("; ");
        DatabaseStructure.Relationship relationship = relationships.get(index);
        result.append(relationship.source().qualifiedName())
            .append('(').append(String.join(", ", relationship.sourceColumns())).append(") -> ")
            .append(relationship.target().qualifiedName())
            .append('(').append(String.join(", ", relationship.targetColumns())).append(")")
            .append(" [").append(relationship.evidence()).append(']');
      }
    }
    return result.toString();
  }

  private static String formatGroupingUsage(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      List<String> grouping) {
    return "  {" + dotted(path) + "}" + branchLabel(branchIndex, branchCount)
        + " -> GROUP BY key (" + String.join(", ", grouping) + ")";
  }

  private static String branchLabel(int branchIndex, int branchCount) {
    return branchCount == 1 ? "" : " branch " + (branchIndex + 1);
  }

  private static void logInferenceUsage(List<String> usage) {
    if (usage.isEmpty()) {
      Log.debug("DQL key inference: no inferred relationships used.");
      return;
    }
    Log.debug("Inferred DQL structure relationships used:\n{}", String.join("\n", usage));
  }

  private static String dotted(HierarchyPath path) {
    return String.join(".", path.getPathParts());
  }

  private record RelationOccurrence(
      QueryShape.IdentifierPart alias,
      String sqlAlias,
      DatabaseStructure.Relation relation) {
  }

  private record ResolvedColumn(RelationOccurrence occurrence, String column) {
  }

  private record BoundItem(RelationOccurrence occurrence, String column, String outputTerminal) {
  }

  private record ScoredOccurrence(RelationOccurrence occurrence, int score) {
  }

  private record IdentityDecision(
      List<String> expressions,
      DatabaseStructure.Relation owner,
      String usage) {
    private IdentityDecision {
      expressions = List.copyOf(expressions);
    }
  }
}
