package blater.nq.inputreader;

public class CsvInputReader extends DelimitedInputReader {
  public CsvInputReader() {
    super("csv", ',');
  }
}
