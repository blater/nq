package blater.nq.runner.sql.cache;

import blater.nq.cli.CacheName;

import java.util.Objects;

/** Bounded metadata for one logically named cache. */
public record LogicalCacheEntry(
    CacheName name,
    long modifiedMillis,
    boolean active) {
  public LogicalCacheEntry {
    Objects.requireNonNull(name, "name");
  }
}
