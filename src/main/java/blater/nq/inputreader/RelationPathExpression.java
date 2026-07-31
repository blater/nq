package blater.nq.inputreader;

import java.util.ArrayList;
import java.util.List;

/** Validated CLI spelling of a relation path before it is resolved against a document. */
public record RelationPathExpression(String canonical) implements Comparable<RelationPathExpression> {
  public RelationPathExpression {
    canonical = normalize(canonical);
  }

  public boolean matches(RelationPath path) {
    return canonical.equals(path.display());
  }

  @Override
  public int compareTo(RelationPathExpression other) {
    return canonical.compareTo(other.canonical);
  }

  @Override
  public String toString() {
    return canonical;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank() || !value.startsWith("/")) {
      throw new IllegalArgumentException("Relation path must begin with '/'.");
    }
    if ("/".equals(value)) return value;
    String[] rawSegments = value.substring(1).split("/", -1);
    List<String> normalized = new ArrayList<>(rawSegments.length);
    for (String raw : rawSegments) {
      if ("*".equals(raw)) normalized.add("*");
      else normalized.add(RelationPath.escape(RelationPath.unescape(raw)));
    }
    return "/" + String.join("/", normalized);
  }
}
