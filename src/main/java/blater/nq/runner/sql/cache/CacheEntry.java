package blater.nq.runner.sql.cache;

public record CacheEntry(
    String cacheFilename,
    long modifiedMillis,
    boolean active) {
}
