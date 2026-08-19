package blater.nql.runner.inference;

import blater.nql.domain.HierarchyPath;
import blater.nql.domain.RepetitionPlacement;
import blater.nql.parser.script.QueryShape;
import blater.nql.parser.script.SelectBlueprint;
import blater.nql.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates database-backed structure-key inference for a parsed DQL SELECT. */
public final class KeyInferencePlanner {

  public CompiledSelect compile(SelectBlueprint blueprint, DatabaseStructure structure) {
    if (blueprint == null) {
      throw new IllegalArgumentException("A SELECT blueprint is required for key inference.");
    }

    Set<HierarchyPath> explicitPaths = explicitPaths(blueprint);
    List<HierarchyPath> planningPaths = planningPaths(blueprint, explicitPaths);
    InferenceRelationBindings bindings = InferenceRelationBindings.bind(
        blueprint, structure, planningPaths);
    InferenceIdentityResolver identityResolver = new InferenceIdentityResolver(structure);

    List<SelectBlueprint.StructureKey> inferred = new ArrayList<>();
    List<String> inferenceUsage = new ArrayList<>();
    Set<HierarchyPath> effectiveKeyPaths = new LinkedHashSet<>(explicitPaths);
    for (HierarchyPath path : planningPaths) {
      if (explicitPaths.contains(path)) {
        continue;
      }
      inferPath(blueprint, path, bindings, identityResolver, effectiveKeyPaths)
          .ifPresent(result -> {
            inferred.add(result.key());
            inferenceUsage.addAll(result.usage());
            effectiveKeyPaths.add(path);
          });
    }

    SelectBlueprint.Compiled compiled = blueprint.compile(inferred);
    logInferenceUsage(inferenceUsage);
    return new CompiledSelect(compiled.sql(), compiled.plan());
  }

  public static QueryShape.ReferencedRelations referencedRelations(SelectBlueprint blueprint) {
    if (blueprint == null) {
      return QueryShape.ReferencedRelations.none();
    }
    return QueryShape.referencedRelations(
        blueprint.branches().stream().map(SelectBlueprint.Branch::queryShape).toList());
  }

  private static Set<HierarchyPath> explicitPaths(SelectBlueprint blueprint) {
    Set<HierarchyPath> paths = new LinkedHashSet<>();
    blueprint.explicitKeys().forEach(key -> paths.add(key.path()));
    return paths;
  }

  private static List<HierarchyPath> planningPaths(
      SelectBlueprint blueprint, Set<HierarchyPath> explicitPaths) {
    Set<HierarchyPath> paths = new LinkedHashSet<>(blueprint.objectPaths());
    paths.addAll(explicitPaths);
    return paths.stream()
        .sorted(Comparator.comparingInt(path -> path.getPathParts().size()))
        .toList();
  }

  private static java.util.Optional<InferredPath> inferPath(
      SelectBlueprint blueprint,
      HierarchyPath path,
      InferenceRelationBindings bindings,
      InferenceIdentityResolver identityResolver,
      Set<HierarchyPath> effectiveKeyPaths) {
    Map<Integer, InferenceIdentityResolver.IdentityDecision> decisions =
        identityResolver.decisionsFor(blueprint, path, bindings);
    long mappedBranches = blueprint.branches().stream().filter(branch -> branch.mapsPath(path)).count();
    if (decisions.isEmpty()) {
      return java.util.Optional.empty();
    }
    if (decisions.size() != mappedBranches) {
      Log.warn(
          "Could not infer a key for every hierarchy-union branch at output path [{}]; preserving row-first behavior for that path.",
          dotted(path));
      return java.util.Optional.empty();
    }
    if (!hasCompatibleArity(decisions)) {
      Log.warn(
          "Could not infer a compatible hierarchy-union key for output path [{}]; preserving row-first behavior for that path.",
          dotted(path));
      return java.util.Optional.empty();
    }

    Map<Integer, RepetitionPlacement> placements = new LinkedHashMap<>();
    decisions.forEach((branchIndex, decision) -> placements.put(
        branchIndex, OutputPlacementPolicy.inferred(path, decision.owner(), effectiveKeyPaths)));
    RepetitionPlacement placement = placements.values().iterator().next();
    if (placements.values().stream().anyMatch(candidate -> !candidate.equals(placement))) {
      Log.warn(
          "Could not infer compatible hierarchy-union placement for output path [{}]; preserving row-first behavior for that path.",
          dotted(path));
      return java.util.Optional.empty();
    }

    SelectBlueprint.StructureKey key = inferredKey(blueprint, path, placement, decisions);
    List<String> usage = decisions.values().stream()
        .map(InferenceIdentityResolver.IdentityDecision::usage)
        .toList();
    return java.util.Optional.of(new InferredPath(key, usage));
  }

  private static boolean hasCompatibleArity(
      Map<Integer, InferenceIdentityResolver.IdentityDecision> decisions) {
    int arity = decisions.values().iterator().next().expressions().size();
    return decisions.values().stream().allMatch(decision -> decision.expressions().size() == arity);
  }

  private static SelectBlueprint.StructureKey inferredKey(
      SelectBlueprint blueprint,
      HierarchyPath path,
      RepetitionPlacement placement,
      Map<Integer, InferenceIdentityResolver.IdentityDecision> decisions) {
    if (blueprint.branches().size() == 1) {
      return SelectBlueprint.StructureKey.inferred(
          path, placement, decisions.values().iterator().next().expressions());
    }
    Map<Integer, List<String>> branchExpressions = new LinkedHashMap<>();
    decisions.forEach((branch, decision) -> branchExpressions.put(branch, decision.expressions()));
    return SelectBlueprint.StructureKey.inferredBranches(path, placement, branchExpressions);
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

  private record InferredPath(SelectBlueprint.StructureKey key, List<String> usage) {
    private InferredPath {
      usage = List.copyOf(usage);
    }
  }
}
