package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.execution.EngineParameterNames;
import blater.nq.parser.script.NestScript;

import java.util.Map;
import java.util.stream.Collectors;

/*
 * Responsibility: Names the supported output formats and their outputWriter implementation.
 */
public enum OutputType {
  XML(new XmlOutputWriter()),
  JSON(new JsonOutputWriter()),
  JSONL(new JsonLinesOutputWriter()),
  YAML(new YamlOutputWriter()),
  CSV(new CsvOutputWriter()),
  TSV(new TsvOutputWriter()),
  TOML(new TomlOutputWriter()),
  MARKDOWN(new MarkdownOutputWriter())
  ;
  final static OutputType DEFAULT_OUTPUT_TYPE = JSON;

  final OutputWriter outputWriter;

  OutputType(OutputWriter outputWriter) {
    this.outputWriter = outputWriter;
  }

  public void write(Hierarchy res) {
    outputWriter.write(res);
  }

  /** Renders without choosing stdout, so reports can target stdout or stderr. */
  public String render(Hierarchy result) {
    return switch (this) {
      case XML -> XmlOutputWriter.render(result);
      case JSON -> JsonOutputWriter.map(result) + System.lineSeparator();
      case JSONL -> JsonOutputWriter.mapLines(result).stream()
          .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
      case YAML -> YamlOutputWriter.map(result);
      case CSV -> CsvOutputWriter.map(result);
      case TSV -> TsvOutputWriter.map(result);
      case TOML -> TomlOutputWriter.map(result);
      case MARKDOWN -> MarkdownOutputWriter.map(result);
    };
  }

  public static OutputType get(NestScript script, Map<String, String> params) {
    String cliOutputType = params.get(EngineParameterNames.OUTPUT_TYPE);
    if (cliOutputType != null) {
      return OutputType.fromName(cliOutputType);
    } else if (script != null && script.outputType() != null) {
      return script.outputType();
    } else
      return DEFAULT_OUTPUT_TYPE;
  }

  public static OutputType fromName(String name) {
    if (name == null || name.isBlank()) {
      return DEFAULT_OUTPUT_TYPE;
    }
    return switch (name.trim().toLowerCase()) {
      case "xml" -> XML;
      case "json" -> JSON;
      case "jsonl" -> JSONL;
      case "csv" -> CSV;
      case "tsv" -> TSV;
      case "toml" -> TOML;
      case "yaml" -> YAML;
      case "markdown" -> MARKDOWN;
      default -> throw new IllegalArgumentException(
          "Unsupported output format [" + name + "]. Expected one of: "
              + "xml, json, jsonl, csv, tsv, yaml, toml, markdown");
    };
  }
}
