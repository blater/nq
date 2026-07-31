package blater.nq.runner.inference;

import blater.nq.domain.HierarchyPath;
import blater.nq.domain.RepetitionPlacement;

import java.util.Set;

/** Chooses inferred output placement without performing SQL or metadata inference. */
final class OutputPlacementPolicy {
  private OutputPlacementPolicy() {
  }

  static RepetitionPlacement inferred(
      HierarchyPath path,
      DatabaseStructure.Relation owner,
      Set<HierarchyPath> effectiveKeyPaths) {
    if (effectiveKeyPaths.stream().anyMatch(path::isBelow)) {
      return RepetitionPlacement.named();
    }
    if (owner != null && recognizedCollectionItem(path, owner.name())) {
      return RepetitionPlacement.named();
    }
    return RepetitionPlacement.anonymous(path.parent());
  }

  private static boolean recognizedCollectionItem(HierarchyPath path, String relationName) {
    HierarchyPath parent = path.parent();
    if (parent == null) return false;
    String relation = IdentifierNaming.normalize(relationName);
    String container = IdentifierNaming.normalize(parent.getTerminalNodeName());
    String item = IdentifierNaming.normalize(path.getTerminalNodeName());
    return relation.equals(container) && IdentifierNaming.singular(relation).equals(item)
        && !container.equals(item);
  }
}
