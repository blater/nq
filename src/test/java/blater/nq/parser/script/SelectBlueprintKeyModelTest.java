package blater.nq.parser.script;

import blater.nq.domain.HierarchyPath;
import blater.nq.domain.KeyOrigin;
import blater.nq.domain.KeyedPath;
import blater.nq.domain.RepetitionPlacement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectBlueprintKeyModelTest {

  private static final HierarchyPath PATH = HierarchyPath.fromDottedPath("result.item");

  @Test
  void explicitKeysCannotUseAnonymousPlacement() {
    assertThrows(IllegalArgumentException.class, () -> new SelectBlueprint.StructureKey(
        PATH,
        RepetitionPlacement.anonymous(PATH.parent()),
        new SelectBlueprint.CommonKeyExpressions(List.of("id")),
        KeyOrigin.EXPLICIT));
    assertThrows(IllegalArgumentException.class, () -> new KeyedPath(
        PATH,
        RepetitionPlacement.anonymous(PATH.parent()),
        List.of("col1"),
        KeyOrigin.EXPLICIT));
  }

  @Test
  void keysRequireNonEmptyCompatibleExpressions() {
    assertThrows(IllegalArgumentException.class,
        () -> new SelectBlueprint.CommonKeyExpressions(List.of()));
    assertThrows(IllegalArgumentException.class, () -> new SelectBlueprint.BranchKeyExpressions(Map.of(
        0, List.of("a"),
        1, List.of("b", "c"))));
    assertThrows(IllegalArgumentException.class, () -> new KeyedPath(
        PATH, RepetitionPlacement.named(), List.of(), KeyOrigin.INFERRED));
  }
}
