package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import blater.nq.inputreader.InputType;
import blater.nq.util.Log;

import java.nio.file.Path;
import java.util.Map;

public record CacheSource(
    String sourcePath,
    InputType inputType,
    String variant,
    String materializationKey) {
  public CacheSource(String sourcePath, InputType inputType, String variant) {
    this(sourcePath, inputType, variant, MaterializationConfiguration.from(Map.of()).canonicalKey());
  }

  public static CacheSource from(String inputFilename, InputType inputType) {
    return from(inputFilename, inputType, Map.of());
  }

  public static CacheSource from(String inputFilename, Map<String, String> parameters) {
    InputType inputType = parameters.containsKey(ParameterParser.INPUT_TYPE_PARAM)
        ? InputType.fromName(parameters.get(ParameterParser.INPUT_TYPE_PARAM))
        : inputTypeFromSource(inputFilename);
    return from(inputFilename, inputType, parameters);
  }

  public static CacheSource from(
      String inputFilename,
      InputType inputType,
      Map<String, String> parameters) {
    String variant = inputType == InputType.PARQUET
        && parameters.containsKey(ParameterParser.PARQUET_RECORD_PARAM)
        ? "record=" + parameters.get(ParameterParser.PARQUET_RECORD_PARAM)
        : "";
    String sourcePath = parameters.containsKey(ParameterParser.STDIN_SOURCE_PARAM)
        ? parameters.get(ParameterParser.STDIN_SOURCE_PARAM)
        : normalizedSourceIdentity(inputFilename);
    return new CacheSource(
        sourcePath,
        inputType,
        variant,
        MaterializationConfiguration.from(parameters).canonicalKey());
  }

  public static Path normalizedSourcePath(String inputFilename) {
    if (inputFilename == null || inputFilename.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "--cache requires an input file.");
    }
    Path path = Path.of(inputFilename);
    return path.toAbsolutePath().normalize();
  }

  public static String normalizedSourceIdentity(String source) {
    return isStandardInputSource(source)
        ? source
        : normalizedSourcePath(source).toString();
  }

  private static boolean isStandardInputSource(String source) {
    return source != null && source.startsWith("stdin:");
  }

  private static InputType inputTypeFromSource(String source) {
    if (!isStandardInputSource(source)) {
      return InputType.fromFilename(source);
    }
    int typeEnd = source.indexOf(':', "stdin:".length());
    if (typeEnd < 0) {
      return Log.fatal(IllegalArgumentException.class,
          "Malformed standard input cache identifier: " + source);
    }
    return InputType.fromName(source.substring("stdin:".length(), typeEnd));
  }

  boolean matches(Metadata metadata) {
    return metadata != null
        && sourcePath.equals(metadata.sourcePath())
        && inputType.name().equals(metadata.inputType())
        && identityText().equals(metadata.identityText());
  }

  String identityText() {
    return "sourcePath=" + sourcePath + '\n'
        + "inputType=" + inputType.name() + '\n'
        + "variant=" + variant + '\n'
        + materializationKey;
  }

  boolean currentLayout() {
    return materializationKey != null
        && materializationKey.lines().anyMatch(
            line -> line.equals("layoutVersion=" + MaterializationConfiguration.LAYOUT_VERSION));
  }

  String materializationVariantId() {
    if (!currentLayout()) return "outdated";
    String defaultKey = MaterializationConfiguration.from(Map.of()).canonicalKey();
    if (defaultKey.equals(materializationKey)) return "default-v" + MaterializationConfiguration.LAYOUT_VERSION;
    return "config-" + PersistentCache.sha256(materializationKey).substring(0, 12);
  }

  String displayVariantId() {
    String materialization = materializationVariantId();
    return variant == null || variant.isBlank()
        ? materialization
        : variant + " + " + materialization;
  }

}
