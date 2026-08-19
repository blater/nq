package blater.nql.cli;

import java.util.Objects;

/** Catalog filtering choice. */
public sealed interface CatalogPattern permits CatalogPattern.All, CatalogPattern.Matching {
  record All() implements CatalogPattern {
  }

  record Matching(String value) implements CatalogPattern {
    public Matching {
      Objects.requireNonNull(value, "value");
    }
  }
}
