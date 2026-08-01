package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.util.Log;

import java.util.Map;

public interface InputReader {

  static InputReader of(InputType type) {
    if (type == InputType.XML)
      return new XmlInputReader();
    else if (type == InputType.JSON)
      return new JsonInputReader();
    else if (type == InputType.JSONL)
      return new JsonLinesInputReader();
    else if (type == InputType.YAML)
      return new YamlInputReader();
    else if (type == InputType.CSV)
      return new CsvInputReader();
    else if (type == InputType.PARQUET)
      return new ParquetInputReader();
    else
      return Log.fatal(IllegalArgumentException.class, "unknown input reader type");
  }

  Hierarchy load(String filename, Map<String, String> parameters);
}
