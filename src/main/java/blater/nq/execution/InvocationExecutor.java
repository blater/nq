package blater.nq.execution;

import blater.nq.Help;
import blater.nq.cli.CacheInvocation;
import blater.nq.cli.CacheNameSelection;
import blater.nq.cli.CapabilitiesInvocation;
import blater.nq.cli.CatalogInvocation;
import blater.nq.cli.CatalogPattern;
import blater.nq.cli.ConvertInvocation;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.HelpInvocation;
import blater.nq.cli.InputSelection;
import blater.nq.cli.NqInvocation;
import blater.nq.cli.RunInvocation;
import blater.nq.cli.ScriptSource;
import blater.nq.cli.VersionInvocation;
import blater.nq.inputreader.InputReader;
import blater.nq.domain.Hierarchy;
import blater.nq.outputwriter.OutputType;
import blater.nq.parser.ScriptLoader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.runner.ScriptRunner;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.cache.CacheExecution;
import blater.nq.runner.sql.cache.CacheHandle;
import blater.nq.runner.sql.cache.CacheLookup;
import blater.nq.runner.sql.cache.PersistentCache;
import blater.nq.report.ReportEnvelope;
import blater.nq.report.CapabilityContract;
import blater.nq.report.HierarchyReportMapper;
import blater.nq.report.ReportWriter;
import blater.nq.util.Log;

import java.io.IOException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

/** Executes typed CLI invocations without rediscovering commands from map keys. */
public final class InvocationExecutor {
  private final InputMaterializer inputMaterializer;
  private final ExecutionTargetResolver targetResolver;

  public InvocationExecutor() {
    this(new InputMaterializer(), new ExecutionTargetResolver());
  }

  InvocationExecutor(
      InputMaterializer inputMaterializer,
      ExecutionTargetResolver targetResolver) {
    this.inputMaterializer = inputMaterializer;
    this.targetResolver = targetResolver;
  }

  public void execute(NqInvocation invocation, InputEnvironment environment) throws Exception {
    try (Log.DebugScope ignored = Log.withDebug(debugEnabled(invocation))) {
      switch (invocation) {
        case HelpInvocation help -> executeHelp(help);
        case VersionInvocation ignoredVersion -> Help.printVersion();
        case CapabilitiesInvocation capabilities -> writeReport(
            "capabilities", CapabilityContract.details(), capabilities.reportFormat());
        case ConvertInvocation convert -> executeConvert(convert, environment);
        case RunInvocation run -> executeRun(run, environment);
        case CatalogInvocation catalog -> executeCatalog(catalog, environment);
        case CacheInvocation cache -> executeCache(cache, environment);
      }
    }
  }

  private static boolean debugEnabled(NqInvocation invocation) {
    return switch (invocation) {
      case RunInvocation run -> run.options().debug();
      case ConvertInvocation convert -> convert.options().debug();
      case CatalogInvocation catalog -> catalog.options().debug();
      case CacheInvocation.Load load -> load.options().debug();
      case CacheInvocation.Use use -> use.debug();
      case CacheInvocation.ListCaches list -> list.debug();
      case CacheInvocation.Clear clear -> clear.debug();
      case HelpInvocation ignored -> false;
      case VersionInvocation ignored -> false;
      case CapabilitiesInvocation ignored -> false;
    };
  }

  private void executeHelp(HelpInvocation invocation) {
    if (invocation.brief()) {
      Help.printBriefHelp();
    } else if (invocation.topic().isEmpty()) {
      Help.printManPage();
    } else {
      Help.printCommandInfo(invocation.topic().getLast());
    }
  }

  private void executeConvert(
      ConvertInvocation invocation,
      InputEnvironment environment) throws IOException {
    warnIfIgnoringStdin(invocation.input(), environment);
    try (MaterializedInput materialized = inputMaterializer.materialize(
        new InputSelection.Provided(invocation.input()), environment)) {
      MaterializedInput.Provided provided = requireInput(materialized, "conversion");
      Map<String, String> parameters = EngineRuntimeParameters.from(
          invocation, provided.engineInput());
      var hierarchy = InputReader.of(invocation.input().format())
          .load(provided.path().toString(), parameters);
      invocation.output().write(hierarchy);
    }
  }

  private void executeRun(
      RunInvocation invocation,
      InputEnvironment environment) throws Exception {
    warnIfIgnoringStdin(invocation.input(), environment);
    try (MaterializedInput materialized = inputMaterializer.materialize(
        invocation.input(), environment)) {
      ExecutionTarget target = targetResolver.resolve(invocation.target(), materialized);
      RunInvocation resolved = new RunInvocation(
          invocation.script(), resolvedSelection(materialized), target,
          invocation.output(), invocation.noKeyInference(), invocation.options());
      Map<String, String> parameters = runtimeParameters(resolved, materialized);
      NestScript script = ScriptParser.parse(loadScript(invocation.script()));
      OutputType.get(script, parameters).write(runScript(script, resolved.target(), parameters));
    }
  }

  private void executeCatalog(
      CatalogInvocation invocation,
      InputEnvironment environment) throws Exception {
    warnIfIgnoringStdin(invocation.input(), environment);
    try (MaterializedInput materialized = inputMaterializer.materialize(
        invocation.input(), environment)) {
      ExecutionTarget target = targetResolver.resolve(invocation.target(), materialized);
      CatalogInvocation resolved = new CatalogInvocation(
          resolvedSelection(materialized), invocation.pattern(), target,
          invocation.reportFormat(), invocation.options());
      Map<String, String> parameters = runtimeParameters(resolved, materialized);
      String pattern = invocation.pattern() instanceof CatalogPattern.Matching matching
          ? matching.value() : null;
      NestScript script = new NestScript(List.of(NestStatement.catalog(pattern)));
      Hierarchy catalog = runScript(script, resolved.target(), parameters);
      writeReport("catalog", HierarchyReportMapper.details(catalog), invocation.reportFormat());
    }
  }

  private void executeCache(
      CacheInvocation invocation,
      InputEnvironment environment) throws Exception {
    switch (invocation) {
      case CacheInvocation.Load load -> executeCacheLoad(load, environment);
      case CacheInvocation.Use use -> {
        CacheHandle handle = PersistentCache.use(use.name(), use.cacheDirectory());
        writeReport("cache.use", Map.of(
            "cache_name", use.name().value(),
            "cache_path", handle.cacheFile().toString(),
            "active", true), use.reportFormat());
      }
      case CacheInvocation.ListCaches list -> {
        List<Map<String, Object>> caches = PersistentCache.listCaches(list.cacheDirectory()).stream()
            .map(entry -> {
              Map<String, Object> fields = new LinkedHashMap<>();
              fields.put("name", entry.name().value());
              fields.put("modified", Instant.ofEpochMilli(entry.modifiedMillis()).toString());
              fields.put("active", entry.active());
              return fields;
            }).toList();
        writeReport("cache.list", Map.of(
            "cache_dir", list.cacheDirectory().toString(),
            "caches", caches), list.reportFormat());
      }
      case CacheInvocation.Clear clear -> {
        int cleared = switch (clear.target()) {
          case CacheInvocation.ClearTarget.Name name ->
              PersistentCache.clearNamed(name.cacheName(), clear.cacheDirectory());
          case CacheInvocation.ClearTarget.OlderThan older ->
              PersistentCache.clearOlderThan(older.age(), clear.cacheDirectory());
          case CacheInvocation.ClearTarget.All ignored ->
              PersistentCache.clearAll(clear.cacheDirectory());
        };
        writeReport("cache.clear", Map.of("cleared", cleared), clear.reportFormat());
      }
    }
  }

  private void executeCacheLoad(
      CacheInvocation.Load invocation,
      InputEnvironment environment) throws Exception {
    warnIfIgnoringStdin(invocation.input(), environment);
    try (MaterializedInput materialized = inputMaterializer.materialize(
        new InputSelection.Provided(invocation.input()), environment)) {
      MaterializedInput.Provided provided = requireInput(materialized, "cache load");
      Map<String, String> parameters = EngineRuntimeParameters.from(
          invocation, provided.engineInput());
      CacheHandle handle = CacheExecution.loadAndActivate(
          invocation.cacheDirectory(), invocation.name(), parameters);
      String name = cacheName(invocation.name(), handle);
      writeReport("cache.load", Map.of(
          "source", sourceDescription(invocation.input()),
          "cache_name", name,
          "cache_path", handle.cacheFile().toString(),
          "active", true), invocation.reportFormat());
    }
  }

  private static Hierarchy runScript(
      NestScript script,
      ExecutionTarget target,
      Map<String, String> parameters) {
    SqlExecutor executor = openExecutor(target, parameters);
    try {
      return ScriptRunner.run(script, parameters, executor);
    } finally {
      executor.close();
    }
  }

  private static SqlExecutor openExecutor(
      ExecutionTarget target,
      Map<String, String> parameters) {
    return switch (target) {
      case ExecutionTarget.Temporary ignored -> CacheExecution.openTemporary(parameters);
      case ExecutionTarget.Jdbc ignored -> new SqlExecutor(parameters);
      case ExecutionTarget.ActiveCache active -> {
        CacheLookup lookup = PersistentCache.active(active.cacheDirectory());
        if (lookup instanceof CacheLookup.Found found) {
          yield CacheExecution.openExisting(found.handle(), parameters);
        }
        throw new IllegalStateException(
            "No active cache exists in " + active.cacheDirectory());
      }
      case ExecutionTarget.NamedCache named -> CacheExecution.openExisting(
          PersistentCache.select(named.name(), named.cacheDirectory()), parameters);
      case ExecutionTarget.InputOrActiveCache ignored -> throw new IllegalStateException(
          "Automatic execution target was not resolved");
    };
  }

  private static String cacheName(CacheNameSelection selection, CacheHandle handle) {
    if (selection instanceof CacheNameSelection.Named named) return named.name().value();
    String filename = handle.cacheFile().getFileName().toString();
    return filename.substring(0, filename.length() - ".mv.db".length());
  }

  private static String sourceDescription(blater.nq.cli.DataInput input) {
    return switch (input.source()) {
      case blater.nq.cli.DataSourceSpec.File file -> file.path().toAbsolutePath().normalize().toString();
      case blater.nq.cli.DataSourceSpec.Text ignored -> "literal data";
      case blater.nq.cli.DataSourceSpec.StandardInput ignored -> "standard input";
    };
  }

  private static void writeReport(
      String command,
      Map<String, ?> details,
      blater.nq.report.ReportFormat format) {
    ReportWriter.write(new ReportEnvelope(command, details), format);
  }

  private static String loadScript(ScriptSource source) throws IOException {
    return switch (source) {
      case ScriptSource.File file -> ScriptLoader.load(file.path().toString());
      case ScriptSource.Text text -> ScriptLoader.loadText(text.value());
    };
  }

  private static InputSelection resolvedSelection(MaterializedInput materialized) {
    return switch (materialized) {
      case MaterializedInput.None ignored -> new InputSelection.None();
      case MaterializedInput.Provided provided -> new InputSelection.Provided(provided.input());
    };
  }

  private static Map<String, String> runtimeParameters(
      NqInvocation invocation,
      MaterializedInput materialized) {
    return switch (materialized) {
      case MaterializedInput.None ignored -> EngineRuntimeParameters.from(invocation);
      case MaterializedInput.Provided provided -> EngineRuntimeParameters.from(
          invocation, provided.engineInput());
    };
  }

  private static MaterializedInput.Provided requireInput(
      MaterializedInput materialized,
      String command) {
    if (materialized instanceof MaterializedInput.Provided provided) return provided;
    throw new IllegalStateException(command + " requires input data");
  }

  private static void warnIfIgnoringStdin(
      blater.nq.cli.DataInput input,
      InputEnvironment environment) {
    if (!(input.source() instanceof blater.nq.cli.DataSourceSpec.StandardInput)
        && environment.hasImmediatelyAvailableInput()) {
      Log.warn("Ignoring standard input because an explicit data source was supplied.");
    }
  }

  private static void warnIfIgnoringStdin(
      InputSelection selection,
      InputEnvironment environment) {
    if (selection instanceof InputSelection.Provided provided) {
      warnIfIgnoringStdin(provided.input(), environment);
    }
  }
}
