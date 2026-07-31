package blater.nq.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
/*
 * Responsibility: Describes when a hierarchy path opens a new node
 * while query rows are being mapped into the result hierarchy.
 */
public class CorrelationRule {
  private final HierarchyPath path;
  private final List<MappingCondition> conditions;

}
