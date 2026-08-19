package blater.nql.runner;

import blater.nql.execution.EngineParameterNames;
import blater.nql.domain.Hierarchy;
import blater.nql.domain.HierarchyPath;
import blater.nql.domain.Node;
import blater.nql.outputwriter.JsonOutputWriter;
import blater.nql.parser.ScriptParser;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.runner.sql.cache.CacheExecution;
import blater.nql.testsupport.H2Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptRunnerCatalogTest {
  @TempDir
  Path tempDir;

  @Test
  void catalogWithoutPatternListsUserTablesWithoutDetails() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (personid integer not null, firstname varchar(80))",
          "create table audit_log (id integer primary key, message varchar(80))",
          "create view person_view as select personid, firstname from person");

      Hierarchy catalog = ScriptRunner.run(ScriptParser.parse("catalog;"), database.jdbcProperties());

      assertEquals("catalog", catalog.getRoot().getName());
      assertTrue(containsValue(values(catalog, "catalog.table.name"), "person"));
      assertTrue(containsValue(values(catalog, "catalog.table.name"), "audit_log"));
      assertFalse(containsValue(values(catalog, "catalog.table.name"), "person_view"));
      assertTrue(values(catalog, "catalog.table.columns.column.name").isEmpty());

      String json = JsonOutputWriter.map(catalog);
      assertTrue(json.contains("\"catalog\""));
      assertTrue(json.contains("\"table\""));
      assertFalse(json.contains("\"columns\""));
    }
  }

  @Test
  void catalogExactTableOutputsOnlyThatTablesDetails() throws Exception {
    try (H2Database database = databaseWithCatalogTables()) {
      Hierarchy catalog = ScriptRunner.run(ScriptParser.parse("catalog person;"), database.jdbcProperties());

      assertEquals(1, values(catalog, "catalog.table.name").size());
      assertTrue(containsValue(values(catalog, "catalog.table.name"), "person"));
      assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "personid"));
      assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "firstname"));
      assertFalse(containsValue(values(catalog, "catalog.table.columns.column.name"), "message"));
    }
  }

  @Test
  void catalogWildcardOutputsDetailsForMatchingTables() throws Exception {
    try (H2Database database = databaseWithCatalogTables()) {
      Hierarchy catalog = ScriptRunner.run(ScriptParser.parse("catalog audit*;"), database.jdbcProperties());

      assertEquals(1, values(catalog, "catalog.table.name").size());
      assertTrue(containsValue(values(catalog, "catalog.table.name"), "audit_log"));
      assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "message"));
    }
  }

  @Test
  void catalogStarOutputsDetailsForAllUserTables() throws Exception {
    try (H2Database database = databaseWithCatalogTables()) {
      Hierarchy catalog = ScriptRunner.run(ScriptParser.parse("catalog *;"), database.jdbcProperties());

      assertTrue(containsValue(values(catalog, "catalog.table.name"), "person"));
      assertTrue(containsValue(values(catalog, "catalog.table.name"), "audit_log"));
      assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "personid"));
      assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "message"));
    }
  }

  @Test
  void catalogStatementOutputsCacheDatabaseTablesAndColumns() throws Exception {
    Path input = tempDir.resolve("customers.json");
    Files.writeString(input, """
        {
          "customers": {
            "person": [
              { "id": "P1", "firstname": "Fred" },
              { "id": "P2", "firstname": "Wilma" }
            ]
          }
        }
        """, StandardCharsets.UTF_8);

    Map<String, String> params = new LinkedHashMap<>();
    params.put(EngineParameterNames.INPUT_FILENAME, input.toString());

    Hierarchy catalog;
    SqlExecutor executor = CacheExecution.openTemporary(params);
    try {
      catalog = ScriptRunner.run(ScriptParser.parse("catalog person;"), params, executor);
    } finally {
      executor.close();
    }

    assertTrue(containsValue(values(catalog, "catalog.table.name"), "person"));
    assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "id"));
    assertTrue(containsValue(values(catalog, "catalog.table.columns.column.name"), "firstname"));
  }

  private H2Database databaseWithCatalogTables() throws Exception {
    H2Database database = new H2Database();
    database.execute(
        "create table person (personid integer not null, firstname varchar(80))",
        "create table audit_log (id integer primary key, message varchar(80))",
        "create view person_view as select personid, firstname from person");
    return database;
  }

  private List<String> values(Hierarchy hierarchy, String path) {
    return hierarchy.select(HierarchyPath.fromDottedPath(path)).stream()
        .map(Node::getValue)
        .toList();
  }

  private boolean containsValue(List<String> values, String expected) {
    return values.stream().anyMatch(value -> expected.equalsIgnoreCase(value));
  }
}
