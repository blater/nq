package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import blater.nq.inputreader.InputType;
import blater.nq.util.Log;

import java.nio.file.Path;
import java.util.Map;

public record CacheSource(
    String sourcePath,
    InputType inputType,
    String variant) {
  public static CacheSource from(String inputFilename, InputType inputType) {
    return from(inputFilename, inputType, Map.of());
  }

  public static CacheSource from(String inputFilename, Map<String, String> parameters) {
    return from(inputFilename, InputType.fromFilename(inputFilename), parameters);
  }

  public static CacheSource from(
      String inputFilename,
      InputType inputType,
      Map<String, String> parameters) {
    String variant = inputType == InputType.PARQUET
        && parameters.containsKey(ParameterParser.PARQUET_RECORD_PARAM)
        ? "record=" + parameters.get(ParameterParser.PARQUET_RECORD_PARAM)
        : "";
    return new CacheSource(
        normalizedSourcePath(inputFilename).toString(),
        inputType,
        variant);
  }

  public static Path normalizedSourcePath(String inputFilename) {
    if (inputFilename == null || inputFilename.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "--cache requires an input file.");
    }
    Path path = Path.of(inputFilename);
    return path.toAbsolutePath().normalize();
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
        + "variant=" + variant + '\n';
  }

}
