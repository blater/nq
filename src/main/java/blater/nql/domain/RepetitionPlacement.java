package blater.nql.domain;

import java.util.Optional;

/** Describes where and how keyed hierarchy items repeat. */
public sealed interface RepetitionPlacement
    permits RepetitionPlacement.NamedItem, RepetitionPlacement.AnonymousItem {

  record NamedItem() implements RepetitionPlacement {
  }

  record AnonymousItem(Optional<HierarchyPath> containerPath) implements RepetitionPlacement {
    public AnonymousItem {
      containerPath = containerPath == null ? Optional.empty() : containerPath;
    }
  }

  static NamedItem named() {
    return new NamedItem();
  }

  static AnonymousItem anonymous(HierarchyPath containerPath) {
    return new AnonymousItem(Optional.ofNullable(containerPath));
  }
}
