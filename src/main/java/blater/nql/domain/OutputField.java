package blater.nql.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
/*
 * Responsibility: Maps one query result column into one result-hierarchy
 * path, with optional value composition and null handling.
 */
@AllArgsConstructor
public class OutputField {
  private final HierarchyPath path;
  private final String sourceColumn;
  //Text appended between the current value and the next value when append(...) composition is used. Null means the last value wins.
  private final String appendText;   // @todo - this needs more explaining - more context.

  private final List<MappingCondition> conditions;
  private final boolean absentOnNull;

  /*
   * Responsibility: Reports whether this field's terminal path segment
   * renders as an attribute-shaped node.
   */
  public boolean isAttribute() {
    return path.isAttribute();
  }
}
