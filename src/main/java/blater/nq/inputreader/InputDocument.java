package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;

import java.util.Objects;

/** A hierarchy plus source facts used only by cache materialization. */
public record InputDocument(Hierarchy hierarchy, SourceStructure sourceStructure) {
  public InputDocument {
    hierarchy = Objects.requireNonNullElseGet(hierarchy, Hierarchy::new);
    if (sourceStructure == null) {
      sourceStructure = SourceStructure.fromHierarchy(hierarchy);
    }
  }

  public static InputDocument fromHierarchy(Hierarchy hierarchy) {
    return new InputDocument(hierarchy, SourceStructure.fromHierarchy(hierarchy));
  }
}
