package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static blater.nq.util.ValueUtil.hasValue;

/*
 * Responsibility: Reads header-based delimited records into a hierarchy.
 */
abstract class DelimitedInputReader implements InputReader {
  private static final String ITEM = "item";

  private final String formatName;
  private final char delimiter;

  DelimitedInputReader(String formatName, char delimiter) {
    this.formatName = formatName;
    this.delimiter = delimiter;
  }

  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    if (!hasValue(filename)) {
      return new Hierarchy();
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return new Hierarchy();
      }
      return read(path, parameters);
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IllegalArgumentException e) {
      return Log.fatal(
          IllegalStateException.class,
          "Malformed " + formatName.toUpperCase() + " input file: " + filename,
          e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    }
  }

  private Hierarchy read(Path path, Map<String, String> parameters) throws IOException {
    CSVFormat format = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreSurroundingSpaces(true)
        .build();

    Node root = new Node(formatName);
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
         CSVParser parser = format.parse(reader)) {
      List<String> headers = parser.getHeaderNames();
      validateHeaders(headers);
      for (CSVRecord record : parser) {
        root.addNode(itemNode(headers, record, parameters));
      }
    }
    return new Hierarchy(root);
  }

  private void validateHeaders(List<String> headers) {
    if (headers.isEmpty()) {
      Log.fatal(
          IllegalArgumentException.class,
          formatName.toUpperCase() + " input requires a header row.");
    }
    for (String header : headers) {
      if (pathParts(header).isEmpty()) {
        Log.fatal(
            IllegalArgumentException.class,
            formatName.toUpperCase() + " header is blank.");
      }
    }
  }

  private static Node itemNode(
      List<String> headers,
      CSVRecord record,
      Map<String, String> parameters) {
    Node item = new Node(ITEM);
    for (String header : headers) {
      addValue(item, pathParts(header), value(record, header, parameters));
    }
    return item;
  }

  private static List<String> pathParts(String header) {
    return Arrays.stream(header.trim().split("\\."))
        .map(String::trim)
        .filter(part -> !part.isEmpty())
        .toList();
  }

  private static String value(CSVRecord record, String header, Map<String, String> parameters) {
    if (!record.isMapped(header) || !record.isSet(header)) {
      return "";
    }
    return Template.expand(record.get(header), parameters);
  }

  private static void addValue(Node parent, List<String> pathParts, String value) {
    Node current = parent;
    for (int index = 0; index < pathParts.size(); index++) {
      String part = pathParts.get(index);
      boolean terminal = index == pathParts.size() - 1;
      if (terminal) {
        Node valueNode = new Node(part);
        valueNode.setValue(value);
        current.addNode(valueNode);
      } else {
        current = childContainer(current, part);
      }
    }
  }

  private static Node childContainer(Node parent, String name) {
    for (Node child : parent.getChildren()) {
      if (child.getName().equals(name) && !child.hasValue() && !child.isAttribute()) {
        return child;
      }
    }
    Node child = new Node(name);
    parent.addNode(child);
    return child;
  }
}
