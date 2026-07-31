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
 * Responsibility: Renders a hierarchy as CSV by flattening record nodes
 * into dotted columns. Hierarchical repeats that cannot fit native CSV
 * are approximated into scalar cells.
 */
public class CsvOutputWriter implements OutputWriter {
  private static final String REPEATED_SCALAR_SEPARATOR = "|";

  @Override
  public void write(Hierarchy result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    System.out.print(map(result)); //NOPMD - suppressed SystemPrintln - legitimate CLI output
  }

  public static String map(Hierarchy hierarchy) {
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
      Map<String, String> row = flattenRecord(record);
      rows.add(row);
      for (String column : row.keySet()) {
        if (!columns.contains(column)) {
          columns.add(column);
        }
      }
    }
    if (columns.isEmpty()) {
      return "";
    }
    return writeCsv(columns, rows);
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

  private static Map<String, String> flattenRecord(Node record) {
    Map<String, String> row = new LinkedHashMap<>();
    flattenNode(record, "", row);
    return row;
  }

  private static void flattenNode(Node node, String path, Map<String, String> row) {
    if (node.isNull()) {
      row.put(pathOrNodeName(path, node), "");
      return;
    }
    if (node.hasValue()) {
      row.put(pathOrNodeName(path, node), node.getValue());
      return;
    }

    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      String childPath = childPath(path, entry.getKey());
      List<Node> children = entry.getValue();
      if (children.size() == 1) {
        flattenNode(children.getFirst(), childPath, row);
      } else if (children.stream().allMatch(CsvOutputWriter::isScalarNode)) {
        row.put(childPath, joinedScalarValues(children));
      } else {
        row.put(childPath, jsonArray(children));
      }
    }
  }

  private static String pathOrNodeName(String path, Node node) {
    return path == null || path.isEmpty() ? node.getName() : path;
  }

  private static String childPath(String parentPath, String childName) {
    return parentPath == null || parentPath.isEmpty()
        ? childName
        : parentPath + "." + childName;
  }

  private static boolean isScalarNode(Node node) {
    return node.isNull() || node.hasValue();
  }

  private static String joinedScalarValues(List<Node> nodes) {
    return nodes.stream()
        .map(node -> node.isNull() ? "" : node.getValue())
        .reduce((left, right) -> left + REPEATED_SCALAR_SEPARATOR + right)
        .orElse("");
  }

  private static String writeCsv(List<String> columns, List<Map<String, String>> rows) {
    StringWriter writer = new StringWriter();
    CSVFormat format = CSVFormat.DEFAULT.builder()
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
      return Log.fatal(IllegalStateException.class, "Could not write CSV output.", e);
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

  private static String jsonArray(List<Node> nodes) {
    StringBuilder json = new StringBuilder("[");
    for (int index = 0; index < nodes.size(); index++) {
      if (index > 0) {
        json.append(",");
      }
      writeJsonValue(json, nodes.get(index));
    }
    json.append("]");
    return json.toString();
  }

  private static void writeJsonValue(StringBuilder json, Node node) {
    if (node.isNull()) {
      json.append("null");
    } else if (node.hasValue()) {
      json.append(jsonQuote(node.getValue()));
    } else {
      writeJsonObject(json, node);
    }
  }

  private static void writeJsonObject(StringBuilder json, Node node) {
    json.append("{");
    boolean first = true;
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      if (!first) {
        json.append(",");
      }
      first = false;
      json.append(jsonQuote(entry.getKey())).append(":");
      List<Node> children = entry.getValue();
      if (children.size() == 1) {
        writeJsonValue(json, children.getFirst());
      } else {
        json.append(jsonArray(children));
      }
    }
    json.append("}");
  }

  private static String jsonQuote(String value) {
    StringBuilder escaped = new StringBuilder(value.length() + 2);
    escaped.append('"');
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (ch < 0x20) {
            escaped.append(String.format("\\u%04x", (int) ch));
          } else {
            escaped.append(ch);
          }
        }
      }
    }
    escaped.append('"');
    return escaped.toString();
  }
}
