package blater.nq.runner.inference;

import blater.nq.ParameterParser;
import blater.nq.domain.HierarchyPath;
import blater.nq.parser.script.NestStatement;
import blater.nq.parser.script.SelectBlueprint;
import blater.nq.parser.script.QueryShape;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.cache.CachedArtifact;
import blater.nq.runner.sql.cache.CacheExecution;
import blater.nq.runner.sql.cache.PersistentCache;
import blater.nq.util.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Single entry point for DQL key inference and its persistent graph. */
public final class KeyInference {
  private static final int GRAPH_VERSION = 2;
  private static final long DEFAULT_EXPIRY_HOURS = 24;

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
    if (Boolean.parseBoolean(parameters.get(ParameterParser.NO_KEY_INFERENCE_PARAM))) {
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
      DatabaseStructure structure = structure(
          executor, parameters, false, KeyInferencePlanner.referencedRelations(blueprint));
      CompiledSelect compiled = new KeyInferencePlanner().compile(blueprint, structure);
      return statement.compiledSelect(compiled.sql(), compiled.plan());
    } catch (Exception ex) {
      Log.warn("Could not infer DQL structure keys; preserving row-first behavior: {}", ex.getMessage());
      SelectBlueprint.Compiled explicit = blueprint.compile(List.of());
      return statement.compiledSelect(explicit.sql(), explicit.plan());
    }
  }

  public static DatabaseStructure refresh(
      SqlExecutor executor,
      Map<String, String> parameters) throws SQLException {
    return structure(executor, parameters, true, QueryShape.ReferencedRelations.none());
  }

  public static DatabaseStructure configureExpiry(
      SqlExecutor executor,
      Map<String, String> parameters,
      long expiryHours) throws SQLException {
    DatabaseStructure structure = structure(
        executor, parameters, false, QueryShape.ReferencedRelations.none());
    DatabaseTargetIdentity target = DatabaseTargetIdentity.from(parameters);
    String identityText = executor.cacheIdentity().orElse(target.identityText());
    Path cacheFile = executor.cacheFile().orElseGet(() ->
        PersistentCache.cacheFile(identityText, parameters));
    PersistentCache.setArtifactExpiry(
        cacheFile, identityText, expiryHours);
    return structure;
  }

  private static DatabaseStructure structure(
      SqlExecutor executor,
      Map<String, String> parameters,
      boolean forceRefresh,
      QueryShape.ReferencedRelations referencedRelations) throws SQLException {
    if (CacheExecution.usesEphemeralCache(parameters)) {
      return DatabaseStructureInferrer.infer(executor.connection());
    }

    DatabaseTargetIdentity target = DatabaseTargetIdentity.from(parameters);
    String identityText = executor.cacheIdentity().orElse(target.identityText());
    Path cacheFile = executor.cacheFile().orElseGet(() ->
        PersistentCache.cacheFile(identityText, parameters));
    Optional<CachedArtifact> cached = PersistentCache.readArtifact(
        cacheFile, identityText);
    long configuredExpiry = configuredExpiry(parameters).orElseGet(() ->
        cached.map(CachedArtifact::expiryHours).orElse(DEFAULT_EXPIRY_HOURS));
    if (!forceRefresh && cached.isPresent() && cached.get().version() == GRAPH_VERSION
        && fresh(cached.get(), configuredExpiry)) {
      try {
        if (configuredExpiry != cached.get().expiryHours()) {
          PersistentCache.setArtifactExpiry(
              cacheFile, identityText, configuredExpiry);
        }
        DatabaseStructure structure = DatabaseStructureCodec.decode(cached.get().payload());
        if (referencedRelations.names().isEmpty()) {
          if (referencedRelations.hasUnsupportedSources()) {
            Log.debug("DQL key inference: unsupported relation sources are excluded from metadata cache validation.");
          }
          return structure;
        }
        if (DatabaseStructureInferrer.matches(
            executor.connection(), structure, referencedRelations.names())) {
          return structure;
        }
        Log.info("Cached database structure changed; refreshing key metadata.");
      } catch (IOException ex) {
        Log.warn("Cached database structure is unreadable and will be rebuilt: {}", ex.getMessage());
      }
    }

    DatabaseStructure inferred = DatabaseStructureInferrer.infer(executor.connection());
    try {
      boolean persisted = PersistentCache.writeArtifact(
          cacheFile,
          identityText,
          target.displayName(),
          GRAPH_VERSION,
          configuredExpiry,
          DatabaseStructureCodec.encode(inferred));
      if (forceRefresh && !persisted) {
        throw new SQLException("Inferred metadata could not be written to the persistent cache.");
      }
    } catch (IOException ex) {
      Log.warn("Could not persist inferred database structure: {}", ex.getMessage());
    }
    return inferred;
  }

  private static boolean fresh(CachedArtifact cached, long expiryHours) {
    if (expiryHours == 0) return false;
    return Instant.ofEpochMilli(cached.refreshedMillis())
        .plus(Duration.ofHours(expiryHours))
        .isAfter(Instant.now());
  }

  private static Optional<Long> configuredExpiry(Map<String, String> parameters) {
    String value = parameters.get(ParameterParser.METADATA_EXPIRY_HOURS_PARAM);
    return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
  }

}
