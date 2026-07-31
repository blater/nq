package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import blater.nq.inputreader.CsvInputReader;
import blater.nq.inputreader.JsonInputReader;
import blater.nq.inputreader.JsonLinesInputReader;
import blater.nq.inputreader.YamlInputReader;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceRelationNamingTest {
  @TempDir
  Path tempDir;

  @Test
  void namedRootCollectionUsesItsDeclaredName() throws Exception {
    Path input = write("customers.json", """
        {"customers":[
          {"id":1,"name":"Alice","city":"London"},
          {"id":2,"name":"Bob","city":"Paris"}
        ]}
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));

      assertEquals(2, database.queryInt("select count(*) from customers"));
      assertEquals(0, tableCount(database, "ITEM"));
    });
  }

  @Test
  void deepCollectionsUseDeclaredNamesAndEffectiveParents() throws Exception {
    Path input = write("deep.json", """
        {"regions":[{
          "id":"R1",
          "customers":[{
            "id":"C1",
            "orders":[{"id":"O1","total":12}]
          }]
        }]}
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));

      assertEquals("R1", database.queryString("select regions_id from customers where id = 'C1'"));
      assertEquals("C1", database.queryString("select customers_id from orders where id = 'O1'"));
    });
  }

  @Test
  void yamlUsesTheSameNamedCollectionContract() throws Exception {
    Path input = write("customers.yaml", """
        customers:
          - id: 1
            name: Alice
          - id: 2
            name: Bob
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new YamlInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));

      assertEquals(2, database.queryInt("select count(*) from customers"));
      assertEquals(0, tableCount(database, "ITEM"));
    });
  }

  @Test
  void anonymousRootObjectRecordsRemainItem() throws Exception {
    Path input = write("records.json", """
        [{"id":1},{"id":2}]
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));

      assertEquals(2, database.queryInt("select count(*) from item"));
    });
  }

  @Test
  void jsonLinesAndCsvUseItemByDefaultAndAcceptRootAliases() throws Exception {
    Path jsonl = write("records.jsonl", """
        {"id":1,"name":"Alice"}
        {"id":2,"name":"Bob"}
        """);
    Path csv = write("records.csv", """
        id,name
        1,Alice
        2,Bob
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonLinesInputReader().read(jsonl.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));
      assertEquals(2, database.queryInt("select count(*) from item"));
    });

    withDatabase((database, executor) -> {
      Map<String, String> alias = aliases("/=customers");
      new HierarchyCacheLoader(executor).load(
          new CsvInputReader().read(csv.toString(), Map.of()),
          MaterializationConfiguration.from(alias));
      assertEquals(2, database.queryInt("select count(*) from customers"));
      assertEquals(0, tableCount(database, "ITEM"));
    });
  }

  @Test
  void rootAndInnerAliasesControlDeepContainmentNames() throws Exception {
    Path input = write("batches.json", """
        [[{"customerId":"C1"}],[{"sku":"P1"}]]
        """);
    Map<String, String> configured = aliases(
        "/=batches", "/0=customers", "/1=products");
    configured.put(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "error");

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(configured));

      assertEquals("1", database.queryString("select batches_id from customers"));
      assertEquals("2", database.queryString("select batches_id from products"));
      assertEquals(0, tableCount(database, "ITEM"));
    });
  }

  @Test
  void scalarOwnerAliasRenamesItsDerivedValueRelation() throws Exception {
    Path input = write("tags.json", """
        {"tags":["vip","active"]}
        """);

    withDatabase((database, executor) -> {
      Map<String, String> alias = aliases("/tags=labels");
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(alias));

      assertEquals(1, database.queryInt("select count(*) from labels"));
      assertEquals(2, database.queryInt("select count(*) from labels_item"));
      assertEquals("1", database.queryString("select labels_id from labels_item order by id limit 1"));
    });
  }

  @Test
  void emptyCollectionsCreateNoTablesButReserveTheirRelationNames() throws Exception {
    Path empty = write("empty.json", "{\"customers\":[]}");
    Path collision = write("empty-collision.json", """
        {"crm":{"items":[]},"catalog":{"items":[{"sku":"P1"}]}}
        """);

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(empty.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of()));
      assertEquals(0, tableCount(database, "CUSTOMERS"));
    });

    withDatabase((database, executor) -> {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> new HierarchyCacheLoader(executor).load(
              new JsonInputReader().read(collision.toString(), Map.of()),
              MaterializationConfiguration.from(Map.of())));
      assertTrue(error.getMessage().contains("/crm/items"));
      assertTrue(error.getMessage().contains("/catalog/items"));
    });
  }

  @Test
  void escapedAndEqualsMemberNamesCanBeAliased() throws Exception {
    Path input = write("escaped.json", """
        {"a/b":{"x=y":[{"id":"R1"}]}}
        """);

    withDatabase((database, executor) -> {
      Map<String, String> configured = aliases("/a~1b/x=y=records");
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(configured));
      assertEquals("R1", database.queryString("select id from records"));
    });
  }

  @Test
  void anAliasNamedItemCannotJoinAnonymousFallbackStorage() throws Exception {
    Path input = write("item-collision.json", """
        [[{"customerId":"C1"}],[{"sku":"P1"}]]
        """);
    Map<String, String> configured = aliases("/0=item", "/1=products");

    withDatabase((database, executor) -> {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> new HierarchyCacheLoader(executor).load(
              new JsonInputReader().read(input.toString(), Map.of()),
              MaterializationConfiguration.from(configured)));
      assertTrue(error.getMessage().contains("Relation name [item]"));
    });
  }

  @Test
  void defaultMergeUnionsAnonymousPathsAndWarnsOnceAboutLostProvenance() throws Exception {
    Path input = write("merged.json", """
        [[{"customerId":"C1"}],[{"sku":"P1"}]]
        """);

    withDatabase((database, executor) -> {
      String warnings = captureStderr(() -> new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(Map.of())));

      assertEquals(4, database.queryInt("select count(*) from item"));
      assertTrue(warnings.contains("rows will not retain query-visible provenance"));
      assertEquals(1, occurrences(warnings, "are being merged into [item]"));
    });
  }

  @Test
  void unknownAliasPathsFailBeforeAnyTableIsCreated() throws Exception {
    Path input = write("known.json", "{\"customers\":[{\"id\":1}]}");

    withDatabase((database, executor) -> {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> new HierarchyCacheLoader(executor).load(
              new JsonInputReader().read(input.toString(), Map.of()),
              MaterializationConfiguration.from(aliases("/missing=records"))));
      assertTrue(error.getMessage().contains("does not match"));
      assertEquals(0, database.queryInt(
          "select count(*) from information_schema.tables where table_schema = 'PUBLIC'"));
    });
  }

  @Test
  void rowsAtOneCanonicalPathUnionOptionalColumnsInStrictMode() throws Exception {
    Path input = write("optional.json", """
        {"customers":[{"id":1,"email":"a@example.test"},{"id":2,"phone":"123"}]}
        """);
    Map<String, String> strict = Map.of(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "error");

    withDatabase((database, executor) -> {
      new HierarchyCacheLoader(executor).load(
          new JsonInputReader().read(input.toString(), Map.of()),
          MaterializationConfiguration.from(strict));
      assertEquals(2, database.queryInt("select count(*) from customers"));
      assertTrue(database.queryString("select email from customers where id = '2'") == null);
      assertTrue(database.queryString("select phone from customers where id = '1'") == null);
    });
  }

  @Test
  void strictModeRejectsSeveralAnonymousPathsUntilAliased() throws Exception {
    Path input = write("anonymous.json", """
        [[{"customerId":"C1"}],[{"sku":"P1"}]]
        """);
    var document = new JsonInputReader().read(input.toString(), Map.of());

    withDatabase((database, executor) -> {
      Map<String, String> strict = Map.of(
          ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "error");
      IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
          () -> new HierarchyCacheLoader(executor).load(
              document, MaterializationConfiguration.from(strict)));
      assertTrue(thrown.getMessage().contains("[/0]"));
    });

    withDatabase((database, executor) -> {
      Map<String, String> configured = new LinkedHashMap<>();
      configured.put(ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "error");
      configured.put(ParameterParser.RELATION_ALIAS_PREFIX + "000000", "/0=customers");
      configured.put(ParameterParser.RELATION_ALIAS_PREFIX + "000001", "/1=products");
      new HierarchyCacheLoader(executor).load(
          document, MaterializationConfiguration.from(configured));

      assertEquals(1, database.queryInt("select count(*) from customers"));
      assertEquals(1, database.queryInt("select count(*) from products"));
      assertEquals(2, database.queryInt("select count(*) from item"));
    });
  }

  @Test
  void distinctNamedPathsRequireExplicitAliases() throws Exception {
    Path input = write("collisions.json", """
        {
          "crm":{"items":[{"customerId":"C1"}]},
          "catalog":{"items":[{"sku":"P1"}]}
        }
        """);
    var document = new JsonInputReader().read(input.toString(), Map.of());

    withDatabase((database, executor) -> assertThrows(IllegalArgumentException.class,
        () -> new HierarchyCacheLoader(executor).load(
            document, MaterializationConfiguration.from(Map.of()))));

    withDatabase((database, executor) -> {
      Map<String, String> configured = new LinkedHashMap<>();
      configured.put(ParameterParser.RELATION_ALIAS_PREFIX + "000000", "/crm/items=crm_items");
      configured.put(ParameterParser.RELATION_ALIAS_PREFIX + "000001", "/catalog/items=catalog_items");
      new HierarchyCacheLoader(executor).load(
          document, MaterializationConfiguration.from(configured));

      assertEquals(1, database.queryInt("select count(*) from crm_items"));
      assertEquals(1, database.queryInt("select count(*) from catalog_items"));
    });
  }

  private Path write(String name, String value) throws Exception {
    Path file = tempDir.resolve(name);
    Files.writeString(file, value);
    return file;
  }

  private Map<String, String> aliases(String... aliases) {
    Map<String, String> configured = new LinkedHashMap<>();
    for (int index = 0; index < aliases.length; index++) {
      configured.put(ParameterParser.RELATION_ALIAS_PREFIX + "%06d".formatted(index), aliases[index]);
    }
    return configured;
  }

  private String captureStderr(ThrowingAction action) throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      action.run();
    } finally {
      System.setErr(original);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private int occurrences(String value, String search) {
    return value.split(java.util.regex.Pattern.quote(search), -1).length - 1;
  }

  private int tableCount(H2Database database, String table) throws Exception {
    return database.queryInt("""
        select count(*) from information_schema.tables
        where table_schema = 'PUBLIC' and table_name = '%s'
        """.formatted(table));
  }

  private void withDatabase(DatabaseAction action) throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        action.run(database, executor);
      } finally {
        executor.close();
      }
    }
  }

  @FunctionalInterface
  private interface DatabaseAction {
    void run(H2Database database, SqlExecutor executor) throws Exception;
  }

  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
