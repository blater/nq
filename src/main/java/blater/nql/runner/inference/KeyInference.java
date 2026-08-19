package blater.nql.runner.inference;

import blater.nql.domain.HierarchyPath;
import blater.nql.execution.EngineParameterNames;
import blater.nql.parser.script.NestStatement;
import blater.nql.parser.script.SelectBlueprint;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.util.Log;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Single entry point for DQL key inference. */
public final class KeyInference {
  private KeyInference() {
  }

  public static NestStatement compile(
      NestStatement statement,
      Map<String, String> parameters,
      SqlExecutor executor) {
    SelectBlueprint blueprint = statement.getSelectBlueprint();
    if (blueprint == null) {
      return statement;
    }
    if (Boolean.parseBoolean(parameters.get(EngineParameterNames.NO_KEY_INFERENCE))) {
      Log.debug("DQL key inference disabled by --no-key-inference.");
      return statement;
    }
    Set<HierarchyPath> explicitPaths = blueprint.explicitKeys().stream()
        .map(SelectBlueprint.StructureKey::path)
        .collect(Collectors.toSet());
    if (blueprint.objectPaths().stream().allMatch(explicitPaths::contains)) {
      Log.debug("DQL key inference: no inferred relationships used (all mapped paths have explicit structure keys).");
      return statement;
    }
    try {
      DatabaseStructure structure = DatabaseStructureInferrer.infer(executor.connection());
      CompiledSelect compiled = new KeyInferencePlanner().compile(blueprint, structure);
      return statement.compiledSelect(compiled.sql(), compiled.plan());
    } catch (Exception ex) {
      Log.warn("Could not infer DQL structure keys; preserving row-first behavior: {}", ex.getMessage());
      SelectBlueprint.Compiled explicit = blueprint.compile(List.of());
      return statement.compiledSelect(explicit.sql(), explicit.plan());
    }
  }

}
