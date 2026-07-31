package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

/*
 * Responsibility: Reads newline-delimited JSON and adapts its records to the
 * same hierarchy shape as a top-level JSON array.
 */
public class JsonLinesInputReader implements InputReader {
  @Override
  public InputDocument read(String filename, Map<String, String> parameters) {
    if (!hasValue(filename)) {
      return InputDocument.fromHierarchy(new Hierarchy());
    }

    Path path = Path.of(filename);
    List<Object> records = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      String line;
      int lineNumber = 0;
      while ((line = reader.readLine()) != null) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        try {
          records.add(JsonInputReader.parse(line));
        } catch (JsonInputReader.JsonParseException e) {
          return Log.fatal(
              IllegalStateException.class,
              "Malformed JSONL input file: " + filename + " at line " + lineNumber,
              e);
        }
      }
      return JsonInputReader.toDocument(records, parameters);
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    }
  }
}
