package blater.nq.cli.parse;

import blater.nq.cli.CacheInvocation;
import blater.nq.cli.CacheNameSelection;
import blater.nq.cli.DataSourceSpec;
import blater.nq.inputreader.InputType;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class CliParserCacheCommandTest {
  private final CliParser parser = new CliParser();

  @Test
  void cacheLoadAcceptsPositionalDataAndGeneratedName() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class, parser.parse("cache", "load", "customers.json"));

    assertInputFile(invocation, "customers.json", InputType.JSON);
    assertInstanceOf(CacheNameSelection.Generated.class, invocation.name());
  }

  @Test
  void cacheCommandWordsAreCaseInsensitive() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class, parser.parse("CaChE", "LoAd", "customers.json"));

    assertInputFile(invocation, "customers.json", InputType.JSON);
  }

  @Test
  void implicitDataWithCacheSelectionResolvesToCacheLoad() {
    var generated = assertInstanceOf(
        CacheInvocation.Load.class, parser.parse("customers.json", "--cache"));
    assertInputFile(generated, "customers.json", InputType.JSON);
    assertInstanceOf(CacheNameSelection.Generated.class, generated.name());

    var named = assertInstanceOf(
        CacheInvocation.Load.class,
        parser.parse("--cache", "customers.json", "--name", "customer-cache"));
    assertInputFile(named, "customers.json", InputType.JSON);
    assertNamed(named, "customer-cache");
  }

  @Test
  void implicitCacheLoadWithoutDataSelectsStandardInput() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class, parser.parse("--cache", "--name", "customer-cache"));

    assertInstanceOf(DataSourceSpec.StandardInput.class, invocation.input().source());
    assertEquals(InputType.JSON, invocation.input().format());
    assertNamed(invocation, "customer-cache");
  }

  @Test
  void cacheLoadAcceptsPositionalDataAndName() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class,
        parser.parse("cache", "load", "customers.json", "customer-cache"));

    assertInputFile(invocation, "customers.json", InputType.JSON);
    assertNamed(invocation, "customer-cache");
  }

  @Test
  void cacheLoadAcceptsReverseNameAndDataWhenExtensionIdentifiesData() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class,
        parser.parse("cache", "load", "customer-cache", "customers.JSON"));

    assertInputFile(invocation, "customers.JSON", InputType.JSON);
    assertNamed(invocation, "customer-cache");
  }

  @Test
  void operandlessCacheLoadSelectsStandardInput() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class, parser.parse("cache", "load"));

    assertInstanceOf(DataSourceSpec.StandardInput.class, invocation.input().source());
    assertEquals(InputType.JSON, invocation.input().format());
    assertInstanceOf(CacheNameSelection.Generated.class, invocation.name());
  }

  @Test
  void cacheLoadAcceptsNamedEquivalentsWithoutRequiringPositionalOperands() {
    var invocation = assertInstanceOf(
        CacheInvocation.Load.class,
        parser.parse("cache", "load", "--input-file", "customers.yaml", "--name", "named"));

    assertInputFile(invocation, "customers.yaml", InputType.YAML);
    assertNamed(invocation, "named");
  }

  @Test
  void cacheUseAcceptsPositionalAndNamedForms() {
    var positional = assertInstanceOf(
        CacheInvocation.Use.class, parser.parse("cache", "use", "Customers"));
    var named = assertInstanceOf(
        CacheInvocation.Use.class, parser.parse("cache", "use", "--name", "Customers"));

    assertEquals("customers", positional.name().value());
    assertEquals(positional, named);
  }

  @Test
  void cacheClearAcceptsPositionalAndNamedCacheNames() {
    var positional = assertInstanceOf(
        CacheInvocation.Clear.class, parser.parse("cache", "clear", "Customers"));
    var named = assertInstanceOf(
        CacheInvocation.Clear.class,
        parser.parse("cache", "clear", "--name", "Customers"));

    assertClearName(positional, "customers");
    assertEquals(positional, named);
  }

  @Test
  void cacheClearAcceptsPositionalAndNamedOlderThanTargets() {
    var positional = assertInstanceOf(
        CacheInvocation.Clear.class,
        parser.parse("cache", "clear", "olderthan", "7d"));
    var named = assertInstanceOf(
        CacheInvocation.Clear.class,
        parser.parse("cache", "clear", "--older-than", "7D"));

    var target = assertInstanceOf(CacheInvocation.ClearTarget.OlderThan.class, positional.target());
    assertEquals(Duration.ofDays(7), target.age());
    assertEquals(positional, named);
  }

  @Test
  void cacheClearAcceptsPositionalAndNamedAllTargets() {
    var positional = assertInstanceOf(
        CacheInvocation.Clear.class, parser.parse("cache", "clear", "ALL"));
    var named = assertInstanceOf(
        CacheInvocation.Clear.class, parser.parse("cache", "clear", "--all"));

    assertInstanceOf(CacheInvocation.ClearTarget.All.class, positional.target());
    assertEquals(positional, named);
  }

  @Test
  void cacheOptionsMayBeInterspersedWithCommandOperands() {
    var invocation = assertInstanceOf(
        CacheInvocation.Clear.class,
        parser.parse(
            "cache", "--cache-dir", "build/test-cache", "clear", "customers",
            "--report-format", "json"));

    assertEquals(
        Path.of("build/test-cache").toAbsolutePath().normalize(), invocation.cacheDirectory());
    assertClearName(invocation, "customers");
  }

  private static void assertInputFile(
      CacheInvocation.Load invocation, String path, InputType inputType) {
    var source = assertInstanceOf(DataSourceSpec.File.class, invocation.input().source());
    assertEquals(Path.of(path), source.path());
    assertEquals(inputType, invocation.input().format());
  }

  private static void assertNamed(CacheInvocation.Load invocation, String expected) {
    var name = assertInstanceOf(CacheNameSelection.Named.class, invocation.name());
    assertEquals(expected, name.name().value());
  }

  private static void assertClearName(CacheInvocation.Clear invocation, String expected) {
    var target = assertInstanceOf(CacheInvocation.ClearTarget.Name.class, invocation.target());
    assertEquals(expected, target.cacheName().value());
  }
}
