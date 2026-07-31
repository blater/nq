package blater.nq.domain;

import java.util.List;

/**
 * Declares the internal result columns that identify one object at an output path.
 */
public record KeyedPath(HierarchyPath path, List<String> sourceColumns, KeyOrigin origin) {
  public KeyedPath(HierarchyPath path, List<String> sourceColumns) {
    this(path, sourceColumns, KeyOrigin.EXPLICIT);
  }

  public KeyedPath {
    sourceColumns = List.copyOf(sourceColumns);
    origin = origin == null ? KeyOrigin.EXPLICIT : origin;
  }

  public boolean inferred() {
    return origin == KeyOrigin.INFERRED;
  }
}
