package blater.nql.runner.sql.cache;

import blater.nql.cli.CacheName;

import java.util.Objects;

/** Result of resolving the active cache without using null or Optional. */
public sealed interface CacheLookup permits CacheLookup.None, CacheLookup.Found {
  record None() implements CacheLookup {
  }

  record Found(CacheName name, CacheHandle handle) implements CacheLookup {
    public Found {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(handle, "handle");
    }
  }
}
