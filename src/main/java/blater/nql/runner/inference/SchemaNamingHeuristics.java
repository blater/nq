package blater.nql.runner.inference;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static blater.nql.runner.inference.DatabaseStructure.KeyEvidence.CONVENTIONAL_KEY;
import static blater.nql.runner.inference.DatabaseStructure.KeyEvidence.LOGICAL_LINK_KEY;
import static blater.nql.runner.inference.DatabaseStructure.RelationshipEvidence.NAMING_AND_TYPE;

/** Deterministic metadata-only naming conventions used after declared constraints. */
public final class SchemaNamingHeuristics {
  private SchemaNamingHeuristics() {
  }

  static List<DatabaseStructure.CandidateKey> conventionalKeys(
      DatabaseStructure.RelationId relation,
      List<DatabaseStructure.Column> columns) {
    String table = DatabaseStructure.normalize(relation.name());
    String singular = IdentifierNaming.singular(table);
    List<DatabaseStructure.CandidateKey> result = new ArrayList<>();
    for (DatabaseStructure.Column column : columns) {
      String name = DatabaseStructure.normalize(column.name());
      if (name.equals("nqlid") || name.equals("id")
          || name.equals(table + "id") || name.equals(singular + "id")
          || name.equals(table + "key") || name.equals(singular + "key")) {
        result.add(new DatabaseStructure.CandidateKey(
            "convention:" + column.name(), List.of(column.name()), CONVENTIONAL_KEY));
      }
    }
    return result;
  }

  static List<DatabaseStructure.Relationship> inferredRelationships(
      List<DatabaseStructure.Relation> relations,
      List<DatabaseStructure.Relationship> declared) {
    List<DatabaseStructure.Relationship> result = new ArrayList<>();
    for (DatabaseStructure.Relation source : relations) {
      for (DatabaseStructure.Column sourceColumn : source.columns()) {
        String stem = referenceStem(sourceColumn.name());
        if (stem == null) continue;
        DatabaseStructure.Relation target = bestTarget(stem, source, relations);
        if (target == null) continue;
        DatabaseStructure.CandidateKey targetKey = target.preferredKey().orElse(null);
        if (targetKey == null || targetKey.columns().size() != 1) continue;
        DatabaseStructure.Column targetColumn = target.column(targetKey.columns().getFirst()).orElse(null);
        if (targetColumn == null || !compatible(sourceColumn.jdbcType(), targetColumn.jdbcType())) continue;
        boolean exists = declared.stream().anyMatch(relationship ->
            relationship.source().equals(source.id())
                && relationship.sourceColumns().stream().anyMatch(sourceColumn.name()::equalsIgnoreCase));
        if (!exists) {
          result.add(new DatabaseStructure.Relationship(
              "inferred:" + source.name() + "." + sourceColumn.name(),
              source.id(),
              List.of(sourceColumn.name()),
              target.id(),
              List.of(targetColumn.name()),
              NAMING_AND_TYPE));
        }
      }
    }
    return result;
  }

  static List<DatabaseStructure.CandidateKey> logicalLinkKeys(
      DatabaseStructure.Relation relation,
      List<DatabaseStructure.Relationship> relationships) {
    List<DatabaseStructure.Relationship> outgoing = relationships.stream()
        .filter(item -> item.source().equals(relation.id()))
        .toList();
    if (outgoing.size() < 2 || outgoing.stream().map(DatabaseStructure.Relationship::target).distinct().count() < 2) {
      return List.of();
    }
    Set<String> columns = new LinkedHashSet<>();
    outgoing.forEach(item -> columns.addAll(item.sourceColumns()));
    return List.of(new DatabaseStructure.CandidateKey(
        "logical-link:" + relation.name(), List.copyOf(columns), LOGICAL_LINK_KEY));
  }

  private static DatabaseStructure.Relation bestTarget(
      String stem,
      DatabaseStructure.Relation source,
      List<DatabaseStructure.Relation> relations) {
    String normalizedStem = DatabaseStructure.normalize(stem);
    return relations.stream()
        .filter(relation -> !relation.id().equals(source.id()))
        .filter(relation -> {
          String table = DatabaseStructure.normalize(relation.name());
          return normalizedStem.equals(table)
              || normalizedStem.equals(IdentifierNaming.singular(table))
              || normalizedStem.endsWith(table)
              || normalizedStem.endsWith(IdentifierNaming.singular(table));
        })
        .sorted((left, right) -> Integer.compare(
            DatabaseStructure.normalize(right.name()).length(),
            DatabaseStructure.normalize(left.name()).length()))
        .findFirst()
        .orElse(null);
  }

  private static String referenceStem(String column) {
    String lower = column.toLowerCase(Locale.ROOT);
    if (lower.endsWith("_id")) return column.substring(0, column.length() - 3);
    if (lower.endsWith("_key")) return column.substring(0, column.length() - 4);
    if (column.endsWith("Id") && column.length() > 2) return column.substring(0, column.length() - 2);
    if (column.endsWith("Key") && column.length() > 3) return column.substring(0, column.length() - 3);
    return null;
  }

  private static boolean compatible(int left, int right) {
    if (left == right) return true;
    return numeric(left) && numeric(right) || textual(left) && textual(right);
  }

  private static boolean numeric(int type) {
    return switch (type) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
           Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE -> true;
      default -> false;
    };
  }

  private static boolean textual(int type) {
    return switch (type) {
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
           Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR -> true;
      default -> false;
    };
  }
}
