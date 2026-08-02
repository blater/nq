package blater.nq.inputreader;

public class TsvInputReader extends DelimitedInputReader {
  public TsvInputReader() {
    super("tsv", '\t');
  }
}
