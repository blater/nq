package blater.nq.outputwriter;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/*
 * Responsibility: Renders a hierarchy as JSON. XML-only node state such
 * as attributes is approximated as ordinary JSON properties.
 */
public class JsonOutputWriter implements OutputWriter {
  private static final Pattern JSON_NUMBER = Pattern.compile(
      "-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

  @Override
  public void write(Hierarchy result) {
    if (result == null || result.isEmpty()) {
      return;
    }
    System.out.println(map(result)); //NOPMD - suppressed SystemPrintln - legitimate CLI output
  }

  public static String map(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null || root.getName() == null) {
      return "{}";
    }

    StringBuilder json = new StringBuilder();
    switch (hierarchy.getRootKind()) {
      case SYNTHETIC_OBJECT -> writeObject(json, root);
      case SYNTHETIC_ARRAY -> writeRootArray(json, root.getChildren());
      case NAMED -> {
        json.append("{");
        writeProperty(json, root.getName(), root);
        json.append("}");
      }
    }
    return json.toString();
  }

  static List<String> mapLines(Hierarchy hierarchy) {
    Node root = hierarchy == null ? null : hierarchy.getRoot();
    if (root == null) {
      return List.of();
    }
    if (hierarchy.getRootKind() != Hierarchy.RootKind.SYNTHETIC_ARRAY) {
      return List.of(map(hierarchy));
    }

    List<String> lines = new ArrayList<>(root.getChildren().size());
    for (Node node : root.getChildren()) {
      StringBuilder json = new StringBuilder();
      if (node.getName() == null || node.getName().isEmpty()) {
        writeObject(json, node);
      } else {
        writeNamedObject(json, node);
      }
      lines.add(json.toString());
    }
    return lines;
  }

  private static void writeProperty(StringBuilder json, String name, Node node) {
    json.append(JsonStringEncoder.quote(name)).append(":");
    writeValue(json, node);
  }

  private static void writeNamedObject(StringBuilder json, Node node) {
    json.append("{");
    writeProperty(json, node.getName(), node);
    json.append("}");
  }

  private static void writeRootArray(StringBuilder json, List<Node> nodes) {
    json.append("[");
    for (int index = 0; index < nodes.size(); index++) {
      if (index > 0) {
        json.append(",");
      }
      Node node = nodes.get(index);
      if (node.getName() == null || node.getName().isEmpty()) {
        writeObject(json, node);
      } else {
        writeNamedObject(json, node);
      }
    }
    json.append("]");
  }

  private static void writeValue(StringBuilder json, Node node) {
    if (node.isCollection()) {
      writeArray(json, node.getChildren());
    } else if (node.isNull()) {
      json.append("null");
    } else if (node.hasValue()) {
      writeScalar(json, node);
    } else {
      writeObject(json, node);
    }
  }

  private static void writeScalar(StringBuilder json, Node node) {
    String value = node.getValue();
    switch (node.getScalarKind()) {
      case NUMBER -> {
        if (JSON_NUMBER.matcher(value).matches()) {
          json.append(value);
        } else {
          json.append(JsonStringEncoder.quote(value));
        }
      }
      case BOOLEAN -> {
        if ("true".equals(value) || "false".equals(value)) {
          json.append(value);
        } else {
          json.append(JsonStringEncoder.quote(value));
        }
      }
      case STRING -> json.append(JsonStringEncoder.quote(value));
    }
  }

  private static void writeObject(StringBuilder json, Node node) {
    json.append("{");
    boolean first = true;
    for (Map.Entry<String, List<Node>> entry : groupedChildren(node).entrySet()) {
      if (!first) {
        json.append(",");
      }
      first = false;

      json.append(JsonStringEncoder.quote(entry.getKey())).append(":");
      List<Node> children = entry.getValue();
      if (children.size() == 1 && !children.getFirst().isArrayItem()) {
        writeValue(json, children.getFirst());
      } else {
        writeArray(json, children);
      }
    }
    json.append("}");
  }

  private static void writeArray(StringBuilder json, List<Node> nodes) {
    json.append("[");
    for (int index = 0; index < nodes.size(); index++) {
      if (index > 0) {
        json.append(",");
      }
      writeValue(json, nodes.get(index));
    }
    json.append("]");
  }

  private static Map<String, List<Node>> groupedChildren(Node node) {
    Map<String, List<Node>> grouped = new LinkedHashMap<>();
    for (Node child : node.getChildren()) {
      grouped.computeIfAbsent(child.getName(), ignored -> new ArrayList<>()).add(child);
    }
    return grouped;
  }

}
