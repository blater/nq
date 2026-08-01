package blater.nq.inputreader;

import blater.nq.util.Log;

import java.util.List;
import java.util.Locale;

public enum InputType {
  XML(".xml"),
  JSON(".json"),
  JSONL(".jsonl"),
  YAML(".yaml", ".yml"),
  CSV(".csv"),
  PARQUET(".parquet");

  private final List<String> extensions;

  InputType(String... extensions) {
    this.extensions = List.of(extensions);
  }

  public static InputType fromFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      return XML;
    }
    InputType type = matchingType(filename);
    if (type != null) {
      return type;
    }
    return Log.fatal(IllegalArgumentException.class, "Unsupported input file type: " + filename);
  }

  public static InputType fromName(String name) {
    if (name == null || name.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "No input type supplied.");
    }
    String normalized = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    return switch (normalized) {
      case "xml" -> XML;
      case "json" -> JSON;
      case "jsonl", "json-lines", "ndjson" -> JSONL;
      case "yaml", "yml" -> YAML;
      case "csv" -> CSV;
      case "parquet" -> PARQUET;
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported input type: " + name
              + ". Expected one of: xml, json, jsonl, yaml, csv, parquet");
    };
  }

  public String fileExtension() {
    return extensions.getFirst();
  }

  public static boolean supportsFilename(String filename) {
    return filename != null && !filename.isBlank() && matchingType(filename) != null;
  }

  private static InputType matchingType(String filename) {
    String normalized = filename.toLowerCase(Locale.ROOT);
    for (InputType type : values()) {
      if (type.extensions.stream().anyMatch(normalized::endsWith)) {
        return type;
      }
    }
    return null;
  }
}
