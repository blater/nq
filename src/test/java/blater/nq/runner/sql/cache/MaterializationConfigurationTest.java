package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterializationConfigurationTest {
  @Test
  void omittedAndExplicitMergeHaveTheSameCanonicalIdentity() {
    var omitted = MaterializationConfiguration.from(Map.of());
    var explicit = MaterializationConfiguration.from(Map.of(
        ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "MERGE"));

    assertEquals(omitted.canonicalKey(), explicit.canonicalKey());
    assertEquals("default-v2", explicit.variantId());
    assertFalse(omitted.explicitlyConfigured());
    assertTrue(explicit.explicitlyConfigured());
  }

  @Test
  void aliasOrderAndTargetCaseDoNotChangeCacheIdentity() {
    Map<String, String> first = aliases("/1=products", "/0=customers");
    Map<String, String> second = aliases("/0=CUSTOMERS", "/1=PRODUCTS");

    assertEquals(
        MaterializationConfiguration.from(first).canonicalKey(),
        MaterializationConfiguration.from(second).canonicalKey());
  }

  @Test
  void exactDuplicateAliasesCollapseButConflictingAliasesFail() {
    var duplicate = MaterializationConfiguration.from(aliases("/0=customers", "/0=CUSTOMERS"));
    assertEquals(1, duplicate.aliases().size());

    assertThrows(IllegalArgumentException.class,
        () -> MaterializationConfiguration.from(aliases("/0=customers", "/0=accounts")));
  }

  @Test
  void validatesAliasSyntaxNamesAndEscapes() {
    assertThrows(IllegalArgumentException.class,
        () -> MaterializationConfiguration.from(aliases("/=bad-name")));
    assertThrows(IllegalArgumentException.class,
        () -> MaterializationConfiguration.from(aliases("/bad~2path=records")));
    assertThrows(IllegalArgumentException.class,
        () -> MaterializationConfiguration.from(aliases("missing=records")));
  }

  private Map<String, String> aliases(String... values) {
    Map<String, String> parameters = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index++) {
      parameters.put(ParameterParser.RELATION_ALIAS_PREFIX + "%06d".formatted(index), values[index]);
    }
    return parameters;
  }
}
