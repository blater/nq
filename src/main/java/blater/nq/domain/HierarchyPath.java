package blater.nq.domain;

import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/*
 * Responsibility: Represents a dot-separated target path inside a
 * query result tree.
 */
@ToString
@EqualsAndHashCode
@Getter
public class HierarchyPath {
  private final List<String> pathParts;
  private final boolean attribute;

  public HierarchyPath(List<String> pathParts) {
    this(pathParts, false);
  }

  public HierarchyPath(List<String> pathParts, boolean attribute) {
    this.pathParts = List.copyOf(pathParts);
    this.attribute = attribute;
  }

  public static HierarchyPath fromDottedPath(String dotSeparatedPath) {
    List<String> parts = Arrays.stream(dotSeparatedPath.split("\\."))
        .filter(part -> !part.isEmpty())
        .toList();
    return fromParts(parts);
  }

  public static HierarchyPath fromSlashPath(String slashSeparatedPath) {
    if (slashSeparatedPath == null || !slashSeparatedPath.startsWith("/") || slashSeparatedPath.contains("//")) {
      throw new IllegalArgumentException("Unsupported hierarchy path: " + slashSeparatedPath);
    }
    List<String> parts = Arrays.stream(slashSeparatedPath.split("/"))
        .filter(part -> !part.isEmpty())
        .toList();
    if (parts.isEmpty() || parts.stream().anyMatch(HierarchyPath::unsupportedPathSegment)) {
      throw new IllegalArgumentException("Unsupported hierarchy path: " + slashSeparatedPath);
    }
    return fromParts(parts);
  }

  private static HierarchyPath fromParts(List<String> rawParts) {
    List<String> parts = new ArrayList<>(rawParts);
    boolean attribute = false;
    if (!parts.isEmpty()) {
      for (int idx = 0; idx < parts.size() - 1; idx++) {
        if (parts.get(idx).startsWith("@")) {
          throw new IllegalArgumentException("Only the terminal path segment can be an attribute.");
        }
      }
      String terminal = parts.getLast();
      if (terminal.startsWith("@")) {
        if (terminal.length() == 1) {
          throw new IllegalArgumentException("Attribute path segment is missing a name.");
        }
        attribute = true;
        parts.set(parts.size() - 1, terminal.substring(1));
      }
    }
    return new HierarchyPath(parts, attribute);
  }


  private static boolean unsupportedPathSegment(String segment) {
    return segment.isBlank()
        || segment.contains("[")
        || segment.contains("]")
        || segment.contains("*")
        || segment.contains("(")
        || segment.contains(")");
  }

  public String getRootName() {
    return pathParts.getFirst();
  }

  public String getTerminalNodeName() {
    return pathParts.getLast();
  }

  public boolean isRoot() {
    return pathParts.size() == 1;
  }

  public HierarchyPath parent() {
    if (isRoot()) {
      return null;
    }
    return new HierarchyPath(new ArrayList<>(pathParts.subList(0, pathParts.size() - 1)));
  }

  public HierarchyPath child(String name) {
    var childSegments = new ArrayList<>(pathParts);
    childSegments.add(name);
    return new HierarchyPath(childSegments);
  }

  public boolean isBelow(HierarchyPath parentPath) {
    if (pathParts.size() <= parentPath.pathParts.size()) {
      return false;
    }
    for (var idx = 0; idx < parentPath.pathParts.size(); idx++) {
      if (!pathParts.get(idx).equals(parentPath.pathParts.get(idx))) {
        return false;
      }
    }
    return true;
  }
}
