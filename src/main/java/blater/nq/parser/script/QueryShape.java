package blater.nq.parser.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Immutable, syntax-only query facts extracted at the parser boundary. */
public record QueryShape(
    List<RelationSource> relationSources,
    Grouping grouping,
    QueryCharacteristics characteristics) {

  public QueryShape {
    relationSources = List.copyOf(relationSources);
  }

  public List<BaseRelation> baseRelations() {
    return relationSources.stream()
        .filter(BaseRelation.class::isInstance)
        .map(BaseRelation.class::cast)
        .toList();
  }

  public boolean hasUnsupportedSource() {
    return relationSources.stream().anyMatch(UnsupportedRelation.class::isInstance);
  }

  public sealed interface RelationSource permits BaseRelation, UnsupportedRelation {
  }

  public record BaseRelation(
      SqlIdentifier qualifiedName,
      Optional<IdentifierPart> alias,
      int occurrenceIndex) implements RelationSource {

    public BaseRelation {
      alias = alias == null ? Optional.empty() : alias;
    }

    public IdentifierPart effectiveAlias() {
      return alias.orElseGet(() -> qualifiedName.parts().getLast());
    }
  }

  public record UnsupportedRelation(String reason) implements RelationSource {
  }

  public sealed interface Grouping permits NoGrouping, KnownGrouping, UnsupportedGrouping {
  }

  public record NoGrouping() implements Grouping {
  }

  public record KnownGrouping(List<ExpressionFacts> expressions) implements Grouping {
    public KnownGrouping {
      expressions = List.copyOf(expressions);
    }
  }

  public record UnsupportedGrouping(String reason) implements Grouping {
  }

  public enum TruthValue {
    YES,
    NO,
    UNKNOWN
  }

  public record QueryCharacteristics(TruthValue distinct, TruthValue containsAggregate) {
  }

  public record SqlIdentifier(List<IdentifierPart> parts) {
    public SqlIdentifier {
      parts = List.copyOf(parts);
      if (parts.isEmpty()) {
        throw new IllegalArgumentException("A SQL identifier must contain at least one part.");
      }
    }

    public String value() {
      return String.join(".", parts.stream().map(IdentifierPart::text).toList());
    }

    public String sql() {
      return String.join(".", parts.stream().map(IdentifierPart::sql).toList());
    }
  }

  public record IdentifierPart(String text, boolean quoted, String sql) {
    public IdentifierPart {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("A SQL identifier part must not be blank.");
      }
      if (sql == null || sql.isBlank()) {
        sql = text;
      }
    }

    public boolean matches(String value) {
      return quoted ? text.equals(value) : text.equalsIgnoreCase(value);
    }

    public String normalized() {
      return quoted ? text : text.toLowerCase(Locale.ROOT);
    }
  }

  public record DirectColumnReference(
      Optional<SqlIdentifier> qualifier,
      IdentifierPart column) {

    public DirectColumnReference {
      qualifier = qualifier == null ? Optional.empty() : qualifier;
    }
  }

  public record TokenSignature(int type, String text) {
    public TokenSignature {
      text = text == null ? "" : text;
    }
  }

  public record ExpressionFacts(
      String originalSql,
      List<TokenSignature> structuralTokens,
      Optional<DirectColumnReference> directColumn,
      TruthValue aggregate) {

    public ExpressionFacts {
      structuralTokens = List.copyOf(structuralTokens);
      directColumn = directColumn == null ? Optional.empty() : directColumn;
    }

    public boolean structurallyEquals(ExpressionFacts other) {
      return other != null && structuralTokens.equals(other.structuralTokens);
    }

    @Override
    public boolean equals(Object value) {
      if (this == value) return true;
      if (!(value instanceof ExpressionFacts other)) return false;
      return structuralTokens.equals(other.structuralTokens)
          && directColumn.equals(other.directColumn)
          && aggregate == other.aggregate;
    }

    @Override
    public int hashCode() {
      return Objects.hash(structuralTokens, directColumn, aggregate);
    }
  }

  public static TruthValue combineAggregate(List<ExpressionFacts> expressions) {
    boolean unknown = false;
    for (ExpressionFacts expression : expressions) {
      if (expression.aggregate() == TruthValue.YES) return TruthValue.YES;
      if (expression.aggregate() == TruthValue.UNKNOWN) unknown = true;
    }
    return unknown ? TruthValue.UNKNOWN : TruthValue.NO;
  }

  public record ReferencedRelations(List<String> names, boolean hasUnsupportedSources) {
    public ReferencedRelations {
      names = List.copyOf(names);
    }

    public static ReferencedRelations none() {
      return new ReferencedRelations(List.of(), false);
    }
  }

  public static ReferencedRelations referencedRelations(List<QueryShape> shapes) {
    List<String> result = new ArrayList<>();
    boolean unsupported = false;
    for (QueryShape shape : shapes) {
      unsupported |= shape.hasUnsupportedSource();
      for (BaseRelation relation : shape.baseRelations()) {
        if (!result.contains(relation.qualifiedName().value())) {
          result.add(relation.qualifiedName().value());
        }
      }
    }
    return new ReferencedRelations(result, unsupported);
  }
}
