package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;

/*
 * Responsibility: Frames JSON values as newline-delimited JSON while sharing
 * all value serialization with JsonOutputWriter.
 */
public class JsonLinesOutputWriter implements OutputWriter {
  @Override
  public void write(Hierarchy result) {
    for (String line : JsonOutputWriter.mapLines(result)) {
      System.out.println(line); //NOPMD - suppressed SystemPrintln - legitimate CLI output
    }
  }
}
