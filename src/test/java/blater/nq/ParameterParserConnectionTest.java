package blater.nq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static blater.nq.ParameterParser.*;

class ParameterParserConnectionTest {
  @TempDir
  Path tempDir;

  @Test
  void buildsMinimalPostgresqlConnection() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--db", "postgres",
        "--database", "customer_data");

    assertEquals("postgresql", params.get(JDBC_DRIVER_PARAM));
    assertEquals(
        "jdbc:postgresql://localhost:5432/customer_data",
        params.get(JDBC_DATABASE_PARAM));
    assertEquals(System.getProperty("user.name"), params.get(JDBC_USERNAME_PARAM));
    assertFalse(params.containsKey(JDBC_PASSWORD_PARAM));
  }

  @Test
  void acceptsEqualsFormsAndPreservesExplicitEmptyValues() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--db=postgresql",
        "--database=customer_data",
        "--host=",
        "--port=",
        "--user=",
        "--password=");

    assertEquals("jdbc:postgresql://:/customer_data", params.get(JDBC_DATABASE_PARAM));
    assertEquals("", params.get(JDBC_USERNAME_PARAM));
    assertEquals("", params.get(JDBC_PASSWORD_PARAM));
  }

  @Test
  void passesMalformedPortThroughToTheJdbcUrl() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--db", "postgresql",
        "--database", "customer_data",
        "--port", "not-a-port");

    assertEquals(
        "jdbc:postgresql://localhost:not-a-port/customer_data",
        params.get(JDBC_DATABASE_PARAM));
  }

  @Test
  void mapsEveryExactJdbcOption() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--jdbc-driver=postgresql",
        "--jdbc-class-name", "example.Driver",
        "--jdbc-database=jdbc:example:test",
        "--jdbc-username", "report_user",
        "--jdbc-password=");

    assertEquals("postgresql", params.get(JDBC_DRIVER_PARAM));
    assertEquals("example.Driver", params.get(JDBC_CLASS_NAME_PARAM));
    assertEquals("jdbc:example:test", params.get(JDBC_DATABASE_PARAM));
    assertEquals("report_user", params.get(JDBC_USERNAME_PARAM));
    assertEquals("", params.get(JDBC_PASSWORD_PARAM));
  }

  @Test
  void exactFormLeavesOmittedCredentialsAbsent() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--jdbc-class-name", "example.Driver",
        "--jdbc-database", "jdbc:example:test");

    assertFalse(params.containsKey(JDBC_USERNAME_PARAM));
    assertFalse(params.containsKey(JDBC_PASSWORD_PARAM));
  }

  @Test
  void directJdbcAssignmentsKeepADataFileAsExternalDatabaseInput() throws Exception {
    Path input = properties("input.json", "{}");

    Map<String, String> params = LegacyCli.parse(
        "select 1;",
        input.toString(),
        "jdbc.class.name=org.h2.Driver",
        "jdbc.database=jdbc:h2:mem:external");

    assertEquals(input.toString(), params.get(INPUT_FILENAME));
    assertEquals("org.h2.Driver", params.get(JDBC_CLASS_NAME_PARAM));
    assertEquals("jdbc:h2:mem:external", params.get(JDBC_DATABASE_PARAM));
    assertFalse(params.containsKey(CACHE_MODE_PARAM));
    assertFalse(blater.nq.runner.sql.cache.CacheExecution.usesEphemeralCache(params));
  }

  @Test
  void commandLineOverridesPropertiesRegardlessOfPlacement() throws Exception {
    Path properties = properties("database.properties", """
        jdbc.driver=h2
        jdbc.database=jdbc:h2:mem:properties
        jdbc.username=properties_user
        jdbc.password=properties_password
        region=properties
        """);

    Map<String, String> propertiesFirst = LegacyCli.parse(
        script().toString(),
        "-p", properties.toString(),
        "--jdbc-database", "jdbc:h2:mem:command",
        "--user", "command_user",
        "region=command");
    Map<String, String> propertiesLast = LegacyCli.parse(
        script().toString(),
        "--jdbc-database", "jdbc:h2:mem:command",
        "--user", "command_user",
        "region=command",
        "-p", properties.toString());

    for (Map<String, String> params : List.of(propertiesFirst, propertiesLast)) {
      assertEquals("h2", params.get(JDBC_DRIVER_PARAM));
      assertEquals("jdbc:h2:mem:command", params.get(JDBC_DATABASE_PARAM));
      assertEquals("command_user", params.get(JDBC_USERNAME_PARAM));
      assertEquals("properties_password", params.get(JDBC_PASSWORD_PARAM));
      assertEquals("command", params.get("region"));
    }
  }

  @Test
  void propertiesOverrideDefaultsButNotExplicitCommandValues() throws Exception {
    Path properties = properties("database.properties", """
        jdbc.username=properties_user
        jdbc.password=properties_password
        """);

    Map<String, String> inherited = LegacyCli.parse(
        script().toString(),
        "--db", "h2",
        "--database", "mem:inherited",
        "-p", properties.toString());
    Map<String, String> overridden = LegacyCli.parse(
        script().toString(),
        "--db", "h2",
        "--database", "mem:overridden",
        "--user", "command_user",
        "-p", properties.toString());

    assertEquals("properties_user", inherited.get(JDBC_USERNAME_PARAM));
    assertEquals("properties_password", inherited.get(JDBC_PASSWORD_PARAM));
    assertEquals("command_user", overridden.get(JDBC_USERNAME_PARAM));
    assertEquals("properties_password", overridden.get(JDBC_PASSWORD_PARAM));
  }

  @Test
  void laterPropertiesFileWinsWithinPropertiesLayer() throws Exception {
    Path first = properties("first.properties", "jdbc.username=first\n");
    Path second = properties("second.properties", "jdbc.username=second\n");

    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "-p", first.toString(),
        "-p", second.toString());

    assertEquals("second", params.get(JDBC_USERNAME_PARAM));
  }

  @Test
  void propertiesFilenameMayContainDashes() throws Exception {
    Path properties = properties("staging-properties.properties", "region=staging\n");

    Map<String, String> params = LegacyCli.parse(
        script().toString(), "-p", properties.toString());

    assertEquals("staging", params.get("region"));
  }

  @Test
  void laterCommandValueWinsWithinCommandLayer() throws Exception {
    Map<String, String> namedThenAssignment = LegacyCli.parse(
        script().toString(),
        "--user", "named",
        "jdbc.username=assignment");
    Map<String, String> assignmentThenNamed = LegacyCli.parse(
        script().toString(),
        "jdbc.username=assignment",
        "--user", "named");

    assertEquals("assignment", namedThenAssignment.get(JDBC_USERNAME_PARAM));
    assertEquals("named", assignmentThenNamed.get(JDBC_USERNAME_PARAM));
  }

  @Test
  void exactDatabaseOptionConsistentlyOverridesSimpleForm() throws Exception {
    Map<String, String> exactLast = LegacyCli.parse(
        script().toString(),
        "--db", "h2",
        "--database", "mem:simple",
        "--jdbc-database", "jdbc:h2:mem:exact");
    Map<String, String> simpleLast = LegacyCli.parse(
        script().toString(),
        "--jdbc-driver", "h2",
        "--jdbc-database", "jdbc:h2:mem:exact",
        "--db", "h2",
        "--database", "mem:simple");

    assertEquals("jdbc:h2:mem:exact", exactLast.get(JDBC_DATABASE_PARAM));
    assertEquals("jdbc:h2:mem:exact", simpleLast.get(JDBC_DATABASE_PARAM));
  }

  @Test
  void explicitClassNameOverridesLogicalDriverFromProperties() throws Exception {
    Path properties = properties("database.properties", "jdbc.driver=h2\n");

    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "-p", properties.toString(),
        "--jdbc-class-name", "example.Driver",
        "--jdbc-database", "jdbc:example:test");

    assertFalse(params.containsKey(JDBC_DRIVER_PARAM));
    assertEquals("example.Driver", params.get(JDBC_CLASS_NAME_PARAM));
  }

  @Test
  void buildsSimpleJdbcUrlsForSupportedDatabaseTypes() throws Exception {
    assertSimpleUrl("h2", "mem:demo", "jdbc:h2:mem:demo");
    assertSimpleUrl("postgresql", "demo", "jdbc:postgresql://localhost:5432/demo");
    assertSimpleUrl("mysql", "demo", "jdbc:mysql://localhost:3306/demo");
    assertSimpleUrl("mariadb", "demo", "jdbc:mariadb://localhost:3306/demo");
    assertSimpleUrl("oracle", "demo", "jdbc:oracle:thin:@//localhost:1521/demo");
    assertSimpleUrl("mssql", "demo", "jdbc:sqlserver://localhost:1433;databaseName=demo");
    assertSimpleUrl("db2", "demo", "jdbc:db2://localhost:50000/demo");
    assertSimpleUrl("hana", "demo", "jdbc:sap://localhost:30015/?databaseName=demo", "30015");
    assertSimpleUrl("informix", "demo", "jdbc:informix-sqli://localhost:9088/demo", "9088");
  }

  @Test
  void simpleConnectionDoesNotExposeParserState() throws Exception {
    Map<String, String> params = LegacyCli.parse(
        script().toString(),
        "--db", "h2",
        "--database", "mem:demo");

    assertFalse(params.keySet().stream().anyMatch(key -> key.startsWith("NSQL_DB_")));
  }

  @Test
  void rejectsStructurallyIncompleteSimpleOptions() throws Exception {
    Path script = script();

    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyCli.parse(script.toString(), "--db", "h2"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyCli.parse(script.toString(), "--host", "db.example"));
    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyCli.parse(
            script.toString(),
            "--db", "h2",
            "--database", "mem:demo",
            "--host", "localhost"));
  }

  @Test
  void separatedOptionsRejectMissingValuesButEqualsAllowsLeadingHyphen() throws Exception {
    Path script = script();

    assertThrows(
        IllegalArgumentException.class,
        () -> LegacyCli.parse(script.toString(), "--password", "--db", "h2"));

    Map<String, String> params = LegacyCli.parse(
        script.toString(),
        "--jdbc-class-name=example.Driver",
        "--jdbc-database=jdbc:example:test",
        "--password=-secret");
    assertEquals("-secret", params.get(JDBC_PASSWORD_PARAM));
  }

  @Test
  void cacheModesHaveExplicitConnectionRules() throws Exception {
    Path input = properties("input.json", "{}");

    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "run", "--script-file", script().toString(),
        "--input-file", input.toString(), "--cache",
        "--db", "h2", "--database", "mem:demo"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse(
        "cache", "list", "--jdbc-username=cache_user"));
  }

  private Path script() throws Exception {
    return properties("query.nq", "select 1 into {result.value};\n");
  }

  private void assertSimpleUrl(String driver, String database, String expected, String... port) throws Exception {
    Map<String, String> params = port.length == 0
        ? LegacyCli.parse(script().toString(), "--db", driver, "--database", database)
        : LegacyCli.parse(
            script().toString(), "--db", driver, "--database", database, "--port", port[0]);
    assertEquals(expected, params.get(JDBC_DATABASE_PARAM));
  }

  private Path properties(String name, String content) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, content);
    return path;
  }
}
