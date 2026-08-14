package blater.nq.runner.sql.cache;

import org.h2.util.ParserUtil;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders hierarchy-derived H2 identifiers without changing their logical names. */
final class CacheSqlIdentifier {
  private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
  private static final Set<String> CONFIGURED_NON_KEYWORDS = Set.of("value");

  private CacheSqlIdentifier() {
  }

  static String render(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      throw new IllegalArgumentException("SQL identifier must not be blank");
    }
    if (SIMPLE_IDENTIFIER.matcher(identifier).matches()
        && (!ParserUtil.isKeyword(identifier, true)
            || CONFIGURED_NON_KEYWORDS.contains(identifier.toLowerCase(Locale.ROOT)))) {
      return identifier;
    }
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }
}
