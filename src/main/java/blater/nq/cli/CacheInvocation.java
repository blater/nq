package blater.nq.cli;

import blater.nq.report.ReportFormat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/** Persistent cache administration operations. */
public sealed interface CacheInvocation extends NqInvocation
    permits CacheInvocation.Load, CacheInvocation.Use,
        CacheInvocation.ListCaches, CacheInvocation.Clear {

  record Load(
      DataInput input,
      CacheNameSelection name,
      Path cacheDirectory,
      ReportFormat reportFormat,
      InvocationOptions options) implements CacheInvocation {
    public Load {
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      Objects.requireNonNull(reportFormat, "reportFormat");
      Objects.requireNonNull(options, "options");
    }
  }

  record Use(
      CacheName name,
      Path cacheDirectory,
      ReportFormat reportFormat,
      boolean debug) implements CacheInvocation {
    public Use {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      Objects.requireNonNull(reportFormat, "reportFormat");
    }
  }

  record ListCaches(
      Path cacheDirectory,
      ReportFormat reportFormat,
      boolean debug) implements CacheInvocation {
    public ListCaches {
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      Objects.requireNonNull(reportFormat, "reportFormat");
    }
  }

  record Clear(
      ClearTarget target,
      Path cacheDirectory,
      ReportFormat reportFormat,
      boolean debug) implements CacheInvocation {
    public Clear {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      Objects.requireNonNull(reportFormat, "reportFormat");
    }
  }

  sealed interface ClearTarget
      permits ClearTarget.Name, ClearTarget.OlderThan, ClearTarget.All {
    record Name(CacheName cacheName) implements ClearTarget {
      public Name {
        Objects.requireNonNull(cacheName, "cacheName");
      }
    }

    record OlderThan(Duration age) implements ClearTarget {
      public OlderThan {
        Objects.requireNonNull(age, "age");
        if (age.isNegative()) {
          throw new IllegalArgumentException("Cache age cannot be negative");
        }
      }
    }

    record All() implements ClearTarget {
    }
  }
}
