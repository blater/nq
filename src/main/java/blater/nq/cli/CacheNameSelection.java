package blater.nq.cli;

import java.util.Objects;

/** Whether cache load generates a name or uses an explicit logical name. */
public sealed interface CacheNameSelection
    permits CacheNameSelection.Generated, CacheNameSelection.Named {
  record Generated() implements CacheNameSelection {
  }

  record Named(CacheName name) implements CacheNameSelection {
    public Named {
      Objects.requireNonNull(name, "name");
    }
  }
}
