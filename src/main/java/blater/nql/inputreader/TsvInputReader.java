package blater.nql.inputreader;

public class TsvInputReader extends DelimitedInputReader {
  public TsvInputReader() {
    super("tsv", '\t');
  }
}
