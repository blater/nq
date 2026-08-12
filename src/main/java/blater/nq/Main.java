package blater.nq;

import blater.nq.inputreader.InputReader;
import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import blater.nq.parser.ScriptLoader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.runner.ScriptRunner;
import blater.nq.runner.sql.cache.CacheExecution;
import blater.nq.runner.sql.cache.PersistentCache;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.report.ReportWriter;
import blater.nq.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blater.nq.ParameterParser.*;

// Responsibility: orchestrates running an nq script.
public class Main {
  public static void main(String... args) throws Exception {
    Log.reportFormat(null);
    Log.reportFormat(ParameterParser.requestedReportFormat(args));
    var params = ParameterParser.parse(args);
    Path stagedInput = stageStandardInput(params);
    try {
      run(params);
    } finally {
      if (stagedInput != null) {
        Files.deleteIfExists(stagedInput);
      }
    }
  }

  private static void run(Map<String, String> params) throws Exception {
    Log.debug(Boolean.parseBoolean(params.get(DEBUG_PARAM)));

    if (params.containsKey(VERSION_PARAM)) {
      Help.printVersion();
    } else if (params.containsKey(HELP_PARAM)) {
      String topic = params.get(HELP_PARAM);
      if (BRIEF_HELP.equals(topic)) {
        Help.printBriefHelp();
      } else if (topic.isBlank()) {
        Help.printManPage();
      } else {
        Help.printCommandInfo(topic);
      }
    } else if (params.containsKey(CACHE_LIST_PARAM)) {
      writeCacheListReport(params);
    } else if (params.containsKey(CACHE_USE_PARAM)) {
      var handle = PersistentCache.use(params.get(CACHE_USE_PARAM), params);
      writeReport(params, "cache use", details(
          "cache_name", handle.cacheFile().getFileName().toString(),
          "cache_path", handle.cacheFile().toString(),
          "active", true));
    } else  if (params.containsKey(CACHE_CLEAR_TARGET_PARAM)
               || params.containsKey(CACHE_CLEAR_ALL_PARAM)
               || params.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)
    ) {
      int cleared = PersistentCache.clear(params);
      writeReport(params, "cache clear", details("cleared", cleared));
    } else if (params.containsKey(CATALOG_PATTERN_PARAM)) {
      String pattern = params.get(CATALOG_PATTERN_PARAM);
      NestScript script = new NestScript(List.of(NestStatement.catalog(pattern.isEmpty() ? null : pattern)));
      execute(script, params);
    } else if (!ParameterParser.hasScript(params)) {
      if (Boolean.parseBoolean(params.get(CACHE_MODE_PARAM))) {
        var handle = CacheExecution.loadAndActivate(params);
        String source = STDIN_INPUT.equals(params.get(INPUT_FILENAME))
            ? "standard input"
            : Path.of(params.get(INPUT_FILENAME)).toAbsolutePath().normalize().toString();
        writeReport(params, "cache load", details(
            "source", source,
            "cache_name", handle.cacheFile().getFileName().toString(),
            "cache_path", handle.cacheFile().toString(),
            "active", true));
      } else {
        convertInput(params);
      }
    } else {
      String inputScript = params.containsKey(SCRIPT_TEXT_PARAM)
          ? ScriptLoader.loadText(params.get(SCRIPT_TEXT_PARAM))
          : ScriptLoader.load(params.get(SCRIPT_FILE_PARAM));
      NestScript script = ScriptParser.parse(inputScript);
      execute(script, params);
    }
  }

  private static Path stageStandardInput(Map<String, String> params) {
    if (!STDIN_INPUT.equals(params.get(INPUT_FILENAME))) {
      return null;
    }

    InputType inputType = InputType.fromName(params.get(INPUT_TYPE_PARAM));
    Path staged = null;
    try {
      staged = Files.createTempFile("nq-stdin-", inputType.fileExtension());
      Files.copy(System.in, staged, StandardCopyOption.REPLACE_EXISTING);
      params.put(INPUT_FILENAME, staged.toString());
      return staged;
    } catch (IOException e) {
      if (staged != null) {
        try {
          Files.deleteIfExists(staged);
        } catch (IOException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
      }
      return Log.fatal(IllegalStateException.class, "Could not read standard input.", e);
    }
  }

  private static void execute(NestScript script, Map<String, String> params) {
    SqlExecutor executor = CacheExecution.openForQuery(params)
        .orElseGet(() -> new SqlExecutor(params));
    try {
      OutputType.get(script, params).write(ScriptRunner.run(script, params, executor));
    } finally {
      executor.close();
    }
  }

  private static void convertInput(Map<String, String> params) {
    String inputFilename = params.get(INPUT_FILENAME);
    InputType inputType = params.containsKey(INPUT_TYPE_PARAM)
        ? InputType.fromName(params.get(INPUT_TYPE_PARAM))
        : InputType.fromFilename(inputFilename);
    var hierarchy = InputReader.of(inputType).load(inputFilename, params);
    OutputType.get(null, params).write(hierarchy);
  }

  private static void writeCacheListReport(Map<String, String> params) {
    List<Map<String, Object>> caches = PersistentCache.listCaches(params).stream()
        .map(entry -> Map.<String, Object>of(
            "name", entry.cacheFilename(),
            "modified", Instant.ofEpochMilli(entry.modifiedMillis()).toString(),
            "active", entry.active()))
        .toList();
    writeReport(params, "cache list", details(
        "state_dir", PersistentCache.stateRoot(params).toString(),
        "cache_dir", PersistentCache.cacheRoot(params).toString(),
        "caches", caches));
  }

  private static void writeReport(
      Map<String, String> params,
      String command,
      Map<String, ?> details) {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("status", "ok");
    report.put("command", command);
    report.putAll(details);
    ReportWriter.write(report, ParameterParser.reportFormat(params));
  }

  private static Map<String, Object> details(Object... values) {
    Map<String, Object> details = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      details.put(values[index].toString(), values[index + 1]);
    }
    return details;
  }
}
