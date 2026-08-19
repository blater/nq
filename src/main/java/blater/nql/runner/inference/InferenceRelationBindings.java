package blater.nql.runner.inference;

import blater.nql.domain.HierarchyPath;
import blater.nql.parser.script.QueryShape;
import blater.nql.parser.script.SelectBlueprint;
import blater.nql.util.Log;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Resolves query relation occurrences and assigns an owning relation to each output path. */
final class InferenceRelationBindings {
  private final Map<Integer, List<RelationOccurrence>> occurrencesByBranch;
  private final Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch;

  private InferenceRelationBindings(
      Map<Integer, List<RelationOccurrence>> occurrencesByBranch,
      Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch) {
    this.occurrencesByBranch = Map.copyOf(occurrencesByBranch);
    this.ownersByBranch = Map.copyOf(ownersByBranch);
  }

  static InferenceRelationBindings bind(
      SelectBlueprint blueprint,
      DatabaseStructure structure,
      List<HierarchyPath> paths) {
    Map<Integer, List<RelationOccurrence>> occurrences = bindOccurrences(blueprint, structure);
    return new InferenceRelationBindings(
        occurrences, bindOwners(blueprint, structure, paths, occurrences));
  }

  List<RelationOccurrence> occurrences(int branchIndex) {
    return occurrencesByBranch.getOrDefault(branchIndex, List.of());
  }

  Map<HierarchyPath, RelationOccurrence> owners(int branchIndex) {
    return ownersByBranch.getOrDefault(branchIndex, Map.of());
  }

  private static Map<Integer, List<RelationOccurrence>> bindOccurrences(
      SelectBlueprint blueprint, DatabaseStructure structure) {
    Map<Integer, List<RelationOccurrence>> result = new LinkedHashMap<>();
    for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
      SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
      List<RelationOccurrence> occurrences = branch.queryShape().baseRelations().stream()
          .map(reference -> occurrence(structure, reference))
          .flatMap(Optional::stream)
          .toList();
      result.put(branchIndex, occurrences);
    }
    return result;
  }

  private static Optional<RelationOccurrence> occurrence(
      DatabaseStructure structure, QueryShape.BaseRelation reference) {
    return resolveRelation(structure, reference.qualifiedName()).map(relation ->
        new RelationOccurrence(reference.effectiveAlias(), reference.effectiveAlias().sql(), relation));
  }

  private static Map<Integer, Map<HierarchyPath, RelationOccurrence>> bindOwners(
      SelectBlueprint blueprint,
      DatabaseStructure structure,
      List<HierarchyPath> paths,
      Map<Integer, List<RelationOccurrence>> occurrencesByBranch) {
    Map<Integer, Map<HierarchyPath, RelationOccurrence>> result = new HashMap<>();
    for (HierarchyPath path : paths) {
      for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
        bindOwner(blueprint, structure, path, branchIndex, occurrencesByBranch, result);
      }
    }
    return result;
  }

  private static void bindOwner(
      SelectBlueprint blueprint,
      DatabaseStructure structure,
      HierarchyPath path,
      int branchIndex,
      Map<Integer, List<RelationOccurrence>> occurrencesByBranch,
      Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch) {
    SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
    if (!branch.mapsPath(path)) {
      return;
    }
    List<RelationOccurrence> occurrences = occurrencesByBranch.getOrDefault(branchIndex, List.of());
    SelectBlueprint.StructureKey explicitKey = blueprint.explicitKeys().stream()
        .filter(key -> key.path().equals(path))
        .findFirst()
        .orElse(null);
    Map<HierarchyPath, RelationOccurrence> owners = ownersByBranch.computeIfAbsent(
        branchIndex, ignored -> new HashMap<>());
    List<InferenceOwnerScorer.ScoredOccurrence> candidates = InferenceOwnerScorer.scoreCandidates(
        path, branch, occurrences, explicitKey, owners.get(path.parent()), structure);
    if (candidates.isEmpty()) {
      return;
    }
    InferenceOwnerScorer.ScoredOccurrence winner = candidates.getFirst();
    warnIfAmbiguous(path, branchIndex, candidates);
    owners.put(path, winner.occurrence());
  }

  private static void warnIfAmbiguous(
      HierarchyPath path,
      int branchIndex,
      List<InferenceOwnerScorer.ScoredOccurrence> candidates) {
    if (candidates.size() < 2 || candidates.getFirst().score() != candidates.get(1).score()) {
      return;
    }
    Log.warn(
        "Ambiguous key inference for output path [{}] branch [{}]; selected [{}] over [{}]. Possible data loss; add an explicit structure key or use --no-key-inference.",
        dotted(path), branchIndex + 1, candidates.getFirst().occurrence().alias().text(),
        candidates.get(1).occurrence().alias().text());
  }

  static Optional<ResolvedColumn> resolveColumn(
      Optional<QueryShape.DirectColumnReference> reference,
      List<RelationOccurrence> occurrences,
      boolean hasUnsupportedSource) {
    if (reference.isEmpty()) {
      return Optional.empty();
    }
    QueryShape.DirectColumnReference column = reference.get();
    if (column.qualifier().isPresent()) {
      return resolveQualifiedColumn(column, occurrences);
    }
    if (hasUnsupportedSource) {
      return Optional.empty();
    }
    List<ResolvedColumn> matches = occurrences.stream()
        .map(occurrence -> columnIn(occurrence.relation(), column.column())
            .map(found -> new ResolvedColumn(occurrence, found.name())))
        .flatMap(Optional::stream)
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static Optional<ResolvedColumn> resolveQualifiedColumn(
      QueryShape.DirectColumnReference column, List<RelationOccurrence> occurrences) {
    QueryShape.SqlIdentifier qualifier = column.qualifier().orElseThrow();
    List<RelationOccurrence> matches = occurrences.stream()
        .filter(occurrence -> qualifierMatches(qualifier, occurrence.alias()))
        .filter(occurrence -> columnIn(occurrence.relation(), column.column()).isPresent())
        .toList();
    if (matches.size() != 1) {
      return Optional.empty();
    }
    String name = columnIn(matches.getFirst().relation(), column.column()).orElseThrow().name();
    return Optional.of(new ResolvedColumn(matches.getFirst(), name));
  }

  private static boolean qualifierMatches(
      QueryShape.SqlIdentifier qualifier, QueryShape.IdentifierPart alias) {
    return qualifier.parts().size() == 1 && qualifier.parts().getFirst().matches(alias.text());
  }

  private static Optional<DatabaseStructure.Column> columnIn(
      DatabaseStructure.Relation relation, QueryShape.IdentifierPart column) {
    return relation.columns().stream().filter(candidate -> column.matches(candidate.name())).findFirst();
  }

  private static Optional<DatabaseStructure.Relation> resolveRelation(
      DatabaseStructure structure, QueryShape.SqlIdentifier identifier) {
    List<DatabaseStructure.Relation> matches = structure.relations().stream()
        .filter(relation -> relationMatches(relation.id(), identifier.parts()))
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  private static boolean relationMatches(
      DatabaseStructure.RelationId id, List<QueryShape.IdentifierPart> parts) {
    return switch (parts.size()) {
      case 1 -> parts.getFirst().matches(id.name());
      case 2 -> id.schema() != null
          && parts.get(0).matches(id.schema()) && parts.get(1).matches(id.name());
      case 3 -> id.catalog() != null && id.schema() != null
          && parts.get(0).matches(id.catalog())
          && parts.get(1).matches(id.schema())
          && parts.get(2).matches(id.name());
      default -> false;
    };
  }

  static List<SelectBlueprint.SelectItem> itemsAt(
      HierarchyPath path, List<SelectBlueprint.SelectItem> items) {
    return items.stream()
        .filter(item -> item.outputPath() != null && path.equals(item.outputPath().parent()))
        .toList();
  }

  private static String dotted(HierarchyPath path) {
    return String.join(".", path.getPathParts());
  }

  record RelationOccurrence(
      QueryShape.IdentifierPart alias,
      String sqlAlias,
      DatabaseStructure.Relation relation) {
  }

  record ResolvedColumn(RelationOccurrence occurrence, String column) {
  }

}
