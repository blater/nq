package blater.nq.runner.sql.cache;

/** Versioned payload stored through the existing persistent-cache lifecycle. */
public record CachedArtifact(byte[] payload, int version, long refreshedMillis, long expiryHours) {
  public CachedArtifact {
    payload = payload.clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
