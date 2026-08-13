package blater.nq.cli;

import java.nio.file.Path;
import java.util.Objects;

/** Database target selected for a run or catalog invocation. */
public sealed interface ExecutionTarget
    permits ExecutionTarget.Temporary, ExecutionTarget.InputOrActiveCache,
        ExecutionTarget.ActiveCache,
        ExecutionTarget.NamedCache, ExecutionTarget.Jdbc {
  record Temporary() implements ExecutionTarget {
  }

  /** Use a temporary database for implicit stdin, otherwise the active cache. */
  record InputOrActiveCache(Path cacheDirectory) implements ExecutionTarget {
    public InputOrActiveCache {
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
    }
  }

  record ActiveCache(Path cacheDirectory) implements ExecutionTarget {
    public ActiveCache {
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
    }
  }

  record NamedCache(Path cacheDirectory, CacheName name) implements ExecutionTarget {
    public NamedCache {
      Objects.requireNonNull(cacheDirectory, "cacheDirectory");
      Objects.requireNonNull(name, "name");
    }
  }

  record Jdbc(JdbcConnectionSpec connection) implements ExecutionTarget {
    public Jdbc {
      Objects.requireNonNull(connection, "connection");
    }
  }
}
