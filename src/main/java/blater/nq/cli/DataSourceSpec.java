package blater.nq.cli;

import java.nio.file.Path;
import java.util.Objects;

/** Source of hierarchical input data. */
public sealed interface DataSourceSpec
    permits DataSourceSpec.File, DataSourceSpec.Text, DataSourceSpec.StandardInput {
  record File(Path path) implements DataSourceSpec {
    public File {
      Objects.requireNonNull(path, "path");
    }
  }

  record Text(String value) implements DataSourceSpec {
    public Text {
      Objects.requireNonNull(value, "value");
    }
  }

  record StandardInput() implements DataSourceSpec {
  }
}
