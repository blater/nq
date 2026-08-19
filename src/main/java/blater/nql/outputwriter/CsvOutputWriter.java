package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;

public class CsvOutputWriter extends DelimitedOutputWriter {
  public CsvOutputWriter() {
    super("csv", ',');
  }

  public static String map(Hierarchy hierarchy) {
    return map(hierarchy, ',', "csv");
  }
}
