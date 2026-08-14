package blater.nq.runner.inference;

import blater.nq.domain.HierarchyPath;

import java.util.List;

/** Formats key-inference diagnostics and SQL identifiers. */
final class InferenceUsageFormatter {
  private InferenceUsageFormatter() {
  }

  static String renderColumn(String column) {
    return simpleIdentifier(column)
        ? column
        : "\"" + column.replace("\"", "\"\"") + "\"";
  }

  static String metadata(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      InferenceRelationBindings.RelationOccurrence occurrence,
      DatabaseStructure.CandidateKey key,
      InferenceRelationBindings.RelationOccurrence parent,
      List<DatabaseStructure.Relationship> relationships) {
    StringBuilder result = new StringBuilder("  {")
        .append(dotted(path)).append("}")
        .append(branchLabel(branchIndex, branchCount))
        .append(" -> ").append(occurrence.alias().text())
        .append(" [").append(occurrence.relation().id().qualifiedName()).append("]")
        .append(", key (").append(String.join(", ", key.columns())).append(")")
        .append(" [").append(key.evidence()).append("]");
    if (parent != null && !relationships.isEmpty()) {
      appendRelationships(result, parent, relationships);
    }
    return result.toString();
  }

  static String grouping(
      HierarchyPath path, int branchIndex, int branchCount, List<String> grouping) {
    return "  {" + dotted(path) + "}" + branchLabel(branchIndex, branchCount)
        + " -> GROUP BY key (" + String.join(", ", grouping) + ")";
  }

  private static boolean simpleIdentifier(String value) {
    if (value == null || value.isEmpty() || !identifierStart(value.charAt(0))) {
      return false;
    }
    for (int index = 1; index < value.length(); index++) {
      if (!identifierPart(value.charAt(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean identifierStart(char character) {
    return Character.isLetter(character) || character == '_' || character == '$';
  }

  private static boolean identifierPart(char character) {
    return Character.isLetterOrDigit(character) || character == '_' || character == '$';
  }

  private static void appendRelationships(
      StringBuilder result,
      InferenceRelationBindings.RelationOccurrence parent,
      List<DatabaseStructure.Relationship> relationships) {
    result.append(", parent ").append(parent.alias().text()).append(" via ");
    for (int index = 0; index < relationships.size(); index++) {
      if (index > 0) {
        result.append("; ");
      }
      appendRelationship(result, relationships.get(index));
    }
  }

  private static void appendRelationship(
      StringBuilder result, DatabaseStructure.Relationship relationship) {
    result.append(relationship.source().qualifiedName())
        .append('(').append(String.join(", ", relationship.sourceColumns())).append(") -> ")
        .append(relationship.target().qualifiedName())
        .append('(').append(String.join(", ", relationship.targetColumns())).append(")")
        .append(" [").append(relationship.evidence()).append(']');
  }

  private static String branchLabel(int branchIndex, int branchCount) {
    return branchCount == 1 ? "" : " branch " + (branchIndex + 1);
  }

  private static String dotted(HierarchyPath path) {
    return String.join(".", path.getPathParts());
  }
}
