package blater.nq.runner.sql.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Owns the atomic filesystem claim for one fresh cache name. */
public final class CachePreparation implements AutoCloseable {
  private final CacheHandle handle;
  private final Path claimFile;
  private boolean closed;

  CachePreparation(CacheHandle handle, Path claimFile) {
    this.handle = Objects.requireNonNull(handle, "handle");
    this.claimFile = Objects.requireNonNull(claimFile, "claimFile");
  }

  public CacheHandle handle() {
    return handle;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    try {
      Files.deleteIfExists(claimFile);
      closed = true;
    } catch (IOException failure) {
      throw new IllegalStateException("Could not release cache-name claim: " + claimFile, failure);
    }
  }
}
