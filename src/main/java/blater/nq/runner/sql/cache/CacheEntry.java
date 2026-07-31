package blater.nq.runner.sql.cache;

public record CacheEntry(
    String sourcePath,
    String inputType,
    long createdMillis,
    boolean active,
    String variantId,
    boolean outdated) {
}
