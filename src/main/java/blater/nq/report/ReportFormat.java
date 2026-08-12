package blater.nq.report;

import blater.nq.outputwriter.OutputType;

/** Names the serialization used for command reports and diagnostics. */
public enum ReportFormat {
  XML,
  JSON,
  JSONL,
  YAML,
  CSV,
  TSV,
  TOML,
  MARKDOWN;

  public static final ReportFormat DEFAULT = MARKDOWN;

  public static ReportFormat fromName(String name) {
    if (name == null || name.isBlank()) {
      return DEFAULT;
    }
    return switch (name.trim().toLowerCase()) {
      case "xml" -> XML;
      case "json" -> JSON;
      case "jsonl" -> JSONL;
      case "yaml" -> YAML;
      case "csv" -> CSV;
      case "tsv" -> TSV;
      case "toml" -> TOML;
      case "markdown", "md" -> MARKDOWN;
      default -> throw new IllegalArgumentException(
          "Unsupported report format [" + name + "]. Expected one of: "
              + "xml, json, jsonl, yaml, csv, tsv, toml, markdown");
    };
  }

  OutputType outputType() {
    return OutputType.valueOf(name());
  }
}
