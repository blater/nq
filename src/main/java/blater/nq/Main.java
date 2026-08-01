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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static blater.nq.ParameterParser.*;

// Responsibility: orchestrates running an nq script.
public class Main {
  public static void main(String... args) throws Exception {
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
        String source = params.containsKey(STDIN_SOURCE_PARAM)
            ? params.get(STDIN_SOURCE_PARAM)
            : CacheSource.normalizedSourcePath(params.get(INPUT_FILENAME)).toString();
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
      params.put(STDIN_SOURCE_PARAM,
          "stdin:" + inputType.name().toLowerCase() + ":sha256:" + sha256(staged));
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

  private static String sha256(Path input) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(input)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available.", e);
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
