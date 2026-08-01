package blater.nq;

import blater.nq.inputreader.InputReader;
import blater.nq.inputreader.InputType;
import blater.nq.outputwriter.OutputType;
import blater.nq.runner.inference.KeyInference;
import blater.nq.parser.ScriptLoader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestScript;
import blater.nq.parser.script.NestStatement;
import blater.nq.runner.ScriptRunner;
import blater.nq.runner.sql.cache.CacheExecution;
import blater.nq.runner.sql.cache.CacheSource;
import blater.nq.runner.sql.cache.PersistentCache;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.util.Log;

import java.util.List;
import java.util.Map;

import static blater.nq.ParameterParser.*;

// Responsibility: orchestrates running an nq script.
public class Main {
  public static void main(String... args) throws Exception {
    var params = ParameterParser.parse(args);
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
      PersistentCache.list(params);
    } else if (params.containsKey(CACHE_USE_PARAM)) {
      var handle = PersistentCache.use(params.get(CACHE_USE_PARAM), params);
      System.out.println("Active cache set to " + handle.source().sourcePath());
    } else  if (params.containsKey(CACHE_CLEAR_TARGET_PARAM)
               || params.containsKey(CACHE_CLEAR_ALL_PARAM)
               || params.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)
    ) {
      PersistentCache.clear(params);
    } else if (params.containsKey(METADATA_REFRESH_PARAM)
        || params.containsKey(METADATA_EXPIRY_HOURS_PARAM)) {
      SqlExecutor executor = CacheExecution.openForQuery(params)
          .orElseGet(() -> new SqlExecutor(params));
      try {
        if (params.containsKey(METADATA_REFRESH_PARAM)) {
          KeyInference.refresh(executor, params);
          System.out.println("Refreshed database key metadata.");
        }
        if (params.containsKey(METADATA_EXPIRY_HOURS_PARAM)) {
          long hours = Long.parseLong(params.get(METADATA_EXPIRY_HOURS_PARAM));
          KeyInference.configureExpiry(executor, params, hours);
          System.out.println("Database key metadata expiry set to " + hours + " hour(s).");
        }
      } finally {
        executor.close();
      }
    } else if (params.containsKey(CATALOG_PATTERN_PARAM)) {
      String pattern = params.get(CATALOG_PATTERN_PARAM);
      NestScript script = new NestScript(List.of(NestStatement.catalog(pattern.isEmpty() ? null : pattern)));
      execute(script, params);
    } else if (!ParameterParser.hasScript(params)) {
      if (Boolean.parseBoolean(params.get(CACHE_MODE_PARAM))) {
        boolean loaded = CacheExecution.loadAndActivate(params);
        String source = CacheSource.normalizedSourcePath(params.get(INPUT_FILENAME)).toString();
        System.out.println((loaded ? "Loaded cache for " : "Using existing cache for ") + source);
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

  private static void execute(NestScript script, Map<String, String> params) {
    OutputType.get(script, params).write(ScriptRunner.run(script, params));
  }

  private static void convertInput(Map<String, String> params) {
    String inputFilename = params.get(INPUT_FILENAME);
    var hierarchy = InputReader.of(InputType.fromFilename(inputFilename)).load(inputFilename, params);
    OutputType.get(null, params).write(hierarchy);
  }
}
