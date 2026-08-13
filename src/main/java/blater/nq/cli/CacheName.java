package blater.nq.cli;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** A normalized public cache name, independent of storage filenames. */
public record CacheName(String value) {
  private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final Set<String> RESERVED = Set.of("all", "olderthan");

  public CacheName {
    Objects.requireNonNull(value, "value");
    value = value.toLowerCase(Locale.ROOT);
    if (!VALID.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Invalid cache name [" + value + "]: expected [a-z0-9][a-z0-9_-]{0,63}");
    }
    if (RESERVED.contains(value)) {
      throw new IllegalArgumentException("Reserved cache name [" + value + "]");
    }
  }
}
