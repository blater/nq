package blater.nql.runner.inference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Immutable query-independent description of visible database structure. */
public record DatabaseStructure(
    List<Relation> relations,
    List<Relationship> relationships,
    long inferredAtMillis) {

  public DatabaseStructure {
    relations = List.copyOf(relations);
    relationships = List.copyOf(relationships);
  }

  public Optional<Relation> relation(String name) {
    if (name == null) {
      return Optional.empty();
    }
    String wanted = normalizeQualified(name);
    List<Relation> matches = relations.stream()
        .filter(relation -> relation.matches(wanted))
        .toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  public List<Relationship> relationshipsFrom(Relation relation) {
    return relationships.stream().filter(item -> item.source().equals(relation.id())).toList();
  }

  static String normalize(String value) {
    return value == null ? "" : value.replace("\"", "")
        .replace("_", "")
        .replace("-", "")
        .toLowerCase(Locale.ROOT);
  }

  static String normalizeQualified(String value) {
    return value == null ? "" : value.replace("\"", "").toLowerCase(Locale.ROOT);
  }

  public record Relation(
      RelationId id,
      String type,
      List<Column> columns,
      List<CandidateKey> candidateKeys) {

    public Relation {
      columns = List.copyOf(columns);
      candidateKeys = List.copyOf(candidateKeys).stream()
          .sorted(CandidateKey.ORDER)
          .toList();
    }

    public String name() {
      return id.name();
    }

    public Optional<Column> column(String name) {
      return columns.stream().filter(column -> column.name().equalsIgnoreCase(unquote(name))).findFirst();
    }

    public Optional<CandidateKey> preferredKey() {
      return candidateKeys.stream().findFirst();
    }

    boolean matches(String normalizedQualified) {
      String name = normalizeQualified(id.name());
      String schemaName = normalizeQualified(id.schema() == null ? id.name() : id.schema() + "." + id.name());
      String fullName = normalizeQualified(id.qualifiedName());
      return normalizedQualified.equals(name)
          || normalizedQualified.equals(schemaName)
          || normalizedQualified.equals(fullName);
    }
  }

  public record RelationId(String catalog, String schema, String name) {

    public String qualifiedName() {
      List<String> parts = new ArrayList<>();
      if (catalog != null && !catalog.isBlank()) parts.add(catalog);
      if (schema != null && !schema.isBlank()) parts.add(schema);
      parts.add(name);
      return String.join(".", parts);
    }
  }

  public record Column(
      String name,
      int jdbcType,
      String typeName,
      boolean nullable,
      int ordinal) {
  }

  public record CandidateKey(
      String name,
      List<String> columns,
      KeyEvidence evidence) {

    static final Comparator<CandidateKey> ORDER = Comparator
        .comparingInt((CandidateKey key) -> key.evidence().rank())
        .thenComparingInt(key -> key.columns().size())
        .thenComparing(key -> key.name() == null ? "" : key.name(), String.CASE_INSENSITIVE_ORDER);

    public CandidateKey {
      columns = List.copyOf(columns);
    }
  }

  public enum KeyEvidence {
    PRIMARY_KEY(0),
    UNIQUE_INDEX(1),
    CONVENTIONAL_KEY(2),
    LOGICAL_LINK_KEY(3);

    private final int rank;

    KeyEvidence(int rank) {
      this.rank = rank;
    }

    public int rank() {
      return rank;
    }
  }

  public record Relationship(
      String name,
      RelationId source,
      List<String> sourceColumns,
      RelationId target,
      List<String> targetColumns,
      RelationshipEvidence evidence) {

    public Relationship {
      sourceColumns = List.copyOf(sourceColumns);
      targetColumns = List.copyOf(targetColumns);
    }
  }

  public enum RelationshipEvidence {
    DECLARED_FOREIGN_KEY,
    NAMING_AND_TYPE
  }

  private static String unquote(String value) {
    if (value == null) return null;
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
