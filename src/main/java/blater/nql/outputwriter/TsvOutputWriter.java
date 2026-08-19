package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;

public class TsvOutputWriter extends DelimitedOutputWriter {
  public TsvOutputWriter() {
    super("tsv", '\t');
  }

  public static String map(Hierarchy hierarchy) {
    return map(hierarchy, '\t', "tsv");
  }
}
