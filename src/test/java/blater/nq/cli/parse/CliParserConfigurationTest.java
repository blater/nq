package blater.nq.cli.parse;

import blater.nq.cli.CacheInvocation;
import blater.nq.cli.ExecutionTarget;
import blater.nq.cli.RunInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliParserConfigurationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void strictConfigProvidesOperationalDefaultsWithoutBecomingTaskParameters() throws IOException {
    Path config = properties("nq.properties", """
        cache.dir=config-cache
        jdbc.database=jdbc:h2:mem:configured
        jdbc.driver=h2
        jdbc.username=agent
        """);
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));

    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse("report.nq", "--config", config.toString()));

    var target = assertInstanceOf(ExecutionTarget.Jdbc.class, invocation.target());
    assertEquals("jdbc:h2:mem:configured", target.connection().url());
    assertEquals(Map.of(), invocation.options().parameters());
  }

  @Test
  void cliAndEnvironmentOverrideConfigInTheFrozenOrder() throws IOException {
    Path config = properties("nq.properties", "cache.dir=config-cache\n");
    var parser = new CliParser(
        Map.of("NQ_CACHE_DIR", temporaryDirectory.resolve("environment-cache").toString()),
        temporaryDirectory.resolve("home"));

    var environment = assertInstanceOf(
        CacheInvocation.ListCaches.class,
        parser.parse("cache", "list", "--config", config.toString()));
    assertEquals(temporaryDirectory.resolve("environment-cache").toAbsolutePath().normalize(),
        environment.cacheDirectory());

    var cli = assertInstanceOf(
        CacheInvocation.ListCaches.class,
        parser.parse(
            "cache", "list", "--config", config.toString(),
            "--cache-dir", temporaryDirectory.resolve("cli-cache").toString()));
    assertEquals(temporaryDirectory.resolve("cli-cache").toAbsolutePath().normalize(),
        cli.cacheDirectory());
  }

  @Test
  void explicitCacheSuppressesInheritedJdbcTarget() throws IOException {
    Path config = properties("nq.properties", "jdbc.database=jdbc:h2:mem:configured\n");
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));

    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse("report.nq", "--cache", "--config", config.toString()));

    assertInstanceOf(ExecutionTarget.ActiveCache.class, invocation.target());
  }

  @Test
  void taskParametersAreSeparateAndCliValuesOverrideTheFile() throws IOException {
    Path parameters = properties("task.properties", "region=eu\nlimit=10\n");
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));

    var invocation = assertInstanceOf(
        RunInvocation.class,
        parser.parse(
            "report.nq", "--params-file", parameters.toString(),
            "--param", "limit=20", "--param", "empty="));

    assertEquals(Map.of("region", "eu", "limit", "20", "empty", ""),
        invocation.options().parameters());
  }

  @Test
  void rejectsUnknownDuplicateAndConflictingOperationalKeys() throws IOException {
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));
    Path unknown = properties("unknown.properties", "surprise=true\n");
    Path duplicate = properties("duplicate.properties", "cache.dir=one\ncache.dir=two\n");
    Path drivers = properties(
        "drivers.properties", "jdbc.driver=h2\njdbc.class.name=org.h2.Driver\n");

    assertUsage(parser, "report.nq", "--config", unknown.toString());
    assertUsage(parser, "report.nq", "--config", duplicate.toString());
    assertUsage(parser, "report.nq", "--config", drivers.toString());
  }

  @Test
  void rejectsReservedAndDuplicateCliTaskParameterNames() throws IOException {
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));
    Path reserved = properties("reserved.properties", "jdbc.password=secret\n");

    assertUsage(parser, "report.nq", "--params-file", reserved.toString());
    assertUsage(parser, "report.nq", "--param", "NQ_CACHE_DIR=bad");
    assertUsage(parser, "report.nq", "--param", "x=1", "--param", "x=2");
  }

  @Test
  void rejectsConfigForConversionAndLegacyPropertiesWithMigrationHint() throws IOException {
    var parser = new CliParser(Map.of(), temporaryDirectory.resolve("home"));
    Path config = properties("nq.properties", "cache.dir=cache\n");

    assertUsage(parser, "convert", "data.json", "--config", config.toString());
    assertUsage(parser, "report.nq", "--properties", config.toString());
    assertUsage(parser, "report.nq", "-p", config.toString());
  }

  private Path properties(String filename, String contents) throws IOException {
    Path path = temporaryDirectory.resolve(filename);
    Files.writeString(path, contents);
    return path;
  }

  private static void assertUsage(CliParser parser, String... arguments) {
    assertThrows(CliUsageException.class, () -> parser.parse(arguments));
  }
}
