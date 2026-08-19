package blater.nql.inputreader;

public class CsvInputReader extends DelimitedInputReader {
  public CsvInputReader() {
    super("csv", ',');
  }
}
