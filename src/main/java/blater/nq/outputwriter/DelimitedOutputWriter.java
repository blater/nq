package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * Responsibility: Renders a hierarchy as delimited records by flattening record nodes
 * into dotted columns and expanding repeated hierarchy nodes into rows.
 */
abstract class DelimitedOutputWriter implements OutputWriter {
  private final String formatName;
  private final char delimiter;

  DelimitedOutputWriter(String formatName, char delimiter) {
    this.formatName = formatName;
    this.delimiter = delimiter;
  }

  @Override
  public void write(Hierarchy result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    System.out.print(map(result, delimiter, formatName)); //NOPMD - legitimate CLI output
  }

  protected static String map(Hierarchy hierarchy, char delimiter, String formatName) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null) {
      return "";
    }

    List<Node> records = hierarchy.getRootKind() == Hierarchy.RootKind.SYNTHETIC_ARRAY
        ? root.getChildren()
        : recordNodes(root);
    List<Map<String, String>> rows = new ArrayList<>(records.size());
    List<String> columns = new ArrayList<>();
    for (Node record : records) {
      List<Map<String, String>> recordRows = flattenNode(record, "");
      rows.addAll(recordRows);
      for (Map<String, String> row : recordRows) {
        for (String column : row.keySet()) {
          if (!columns.contains(column)) {
            columns.add(column);
          }
        }
      }
    }
    if (columns.isEmpty()) {
      return "";
    }
    return writeDelimited(columns, rows, delimiter, formatName);
  }

  private static List<Node> recordNodes(Node root) {
    Map<String, List<Node>> children = groupedChildren(root);
    if (children.size() == 1) {
      List<Node> onlyChildGroup = children.values().iterator().next();
      if (onlyChildGroup.size() == 1 && onlyChildGroup.getFirst().isCollection()) {
        return onlyChildGroup.getFirst().getChildren();
      }
      if (onlyChildGroup.size() > 1
          || onlyChildGroup.size() == 1 && onlyChildGroup.getFirst().isArrayItem()) {
        return onlyChildGroup;
      }
    }
    return List.of(root);
  }

  private static List<Map<String, String>> flattenNode(Node node, String path) {
    if (node.isNull()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put(pathOrNodeName(path, node), "");
      return List.of(row);
    }
    if (node.hasValue()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put(pathOrNodeName(path, node), node.getValue());
      return List.of(row);
    }

    List<Map<String, String>> rows = new ArrayList<>();
    rows.add(new LinkedHashMap<>());
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      String childPath = childPath(path, entry.getKey());
      List<Map<String, String>> childRows = new ArrayList<>();
      for (Node child : entry.getValue()) {
        childRows.addAll(flattenNode(child, childPath));
      }
      rows = mergeRows(rows, childRows);
    }
    return rows;
  }

  private static String pathOrNodeName(String path, Node node) {
    return path == null || path.isEmpty() ? node.getName() : path;
  }

  private static String childPath(String parentPath, String childName) {
    return parentPath == null || parentPath.isEmpty()
        ? childName
        : parentPath + "." + childName;
  }

  private static List<Map<String, String>> mergeRows(
      List<Map<String, String>> existingRows,
      List<Map<String, String>> additionalRows) {

    List<Map<String, String>> mergedRows = new ArrayList<>();
    for (Map<String, String> existing : existingRows) {
      for (Map<String, String> additional : additionalRows) {
        Map<String, String> merged = new LinkedHashMap<>(existing);
        merged.putAll(additional);
        mergedRows.add(merged);
      }
    }
    return mergedRows;
  }

  private static String writeDelimited(
      List<String> columns,
      List<Map<String, String>> rows,
      char delimiter,
      String formatName) {
    StringWriter writer = new StringWriter();
    CSVFormat format = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setHeader(columns.toArray(String[]::new))
        .setRecordSeparator("\n")
        .build();
    try (CSVPrinter printer = new CSVPrinter(writer, format)) {
      for (Map<String, String> row : rows) {
        List<String> values = columns.stream()
            .map(column -> row.getOrDefault(column, ""))
            .toList();
        printer.printRecord(values);
      }
    } catch (IOException e) {
      return Log.fatal(
          IllegalStateException.class,
          "Could not write " + formatName.toUpperCase() + " output.",
          e);
    }
    return writer.toString();
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }
}
