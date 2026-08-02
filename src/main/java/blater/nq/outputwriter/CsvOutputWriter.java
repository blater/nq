package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;

public class CsvOutputWriter extends DelimitedOutputWriter {
  public CsvOutputWriter() {
    super("csv", ',');
  }

  public static String map(Hierarchy hierarchy) {
    return map(hierarchy, ',', "csv");
  }
}
