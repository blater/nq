package blater.nql.outputwriter;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Responsibility: Renders a hierarchy as a human-readable Markdown table by
 * flattening nested values into dotted columns and repeated objects into rows.
 */
public final class MarkdownOutputWriter implements OutputWriter {
  private static final int COLUMN_WIDTH = 25;

  @Override
  public void write(Hierarchy result) {
    String markdown = map(result);
    if (!markdown.isEmpty()) {
      System.out.print(markdown); //NOPMD - suppressed SystemPrintln - legitimate CLI output
    }
  }

  public static String map(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null) {
      return "";
    }

    List<Map<String, String>> rows = new ArrayList<>();
    Set<String> columns = new LinkedHashSet<>();
    List<Node> records = hierarchy.getRootKind() == Hierarchy.RootKind.SYNTHETIC_ARRAY
        ? root.getChildren()
        : recordNodes(root);
    for (Node record : records) {
      List<Map<String, String>> recordRows = flattenNode(record, "");
      rows.addAll(recordRows);
      recordRows.forEach(row -> columns.addAll(row.keySet()));
    }
    if (columns.isEmpty()) {
      return "";
    }
    return writeTable(new ArrayList<>(columns), rows);
  }

  private static List<Node> recordNodes(Node root) {
    Map<String, List<Node>> children = groupedChildren(root);
    if (children.size() == 1) {
      List<Node> onlyChildGroup = children.values().iterator().next();
      if (onlyChildGroup.size() == 1 && onlyChildGroup.getFirst().isCollection()) {
        return onlyChildGroup.getFirst().getChildren();
      }
      if (onlyChildGroup.size() > 1) {
        return onlyChildGroup;
      }
    }
    return List.of(root);
  }

  private static List<Map<String, String>> flattenNode(Node node, String path) {
    if (node.isNull() || node.hasValue()) {
      Map<String, String> row = new LinkedHashMap<>();
      row.put(path.isEmpty() ? node.getName() : path, node.isNull() ? "" : node.getValue());
      return List.of(row);
    }

    List<Map<String, String>> rows = new ArrayList<>();
    rows.add(new LinkedHashMap<>());
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
      List<Map<String, String>> childRows = new ArrayList<>();
      for (Node child : entry.getValue()) {
        childRows.addAll(flattenNode(child, childPath));
      }
      rows = mergeRows(rows, childRows);
    }
    return rows;
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

  private static String writeTable(List<String> columns, List<Map<String, String>> rows) {
    StringBuilder markdown = new StringBuilder();
    writeRow(markdown, columns);
    writeRow(markdown, columns.stream().map(ignored -> "-".repeat(COLUMN_WIDTH)).toList());
    for (Map<String, String> row : rows) {
      writeRow(markdown, columns.stream()
          .map(column -> row.getOrDefault(column, ""))
          .toList());
    }
    return markdown.toString();
  }

  private static void writeRow(StringBuilder markdown, List<String> cells) {
    markdown.append("|");
    for (String cell : cells) {
      markdown.append(" ").append(fixedWidth(cell)).append(" |");
    }
    markdown.append("\n");
  }

  private static String fixedWidth(String value) {
    String cell = value == null ? "" : value;
    cell = cell.substring(0, Math.min(cell.length(), COLUMN_WIDTH));
    return cell + " ".repeat(COLUMN_WIDTH - cell.length());
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }
}
