package blater.nql.runner.sql.cache;

import java.nio.file.Path;

public record CacheHandle(Path cacheFile, String jdbcUrl, boolean needsLoad) {
}
