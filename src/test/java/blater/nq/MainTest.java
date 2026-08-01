package blater.nq;

import blater.nq.parser.HiqlSyntaxException;
import blater.nq.runner.sql.cache.CacheExecution;
import blater.nq.testsupport.ParquetTestFiles;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
  @TempDir
  Path tempDir;

  @BeforeEach
  void clearActiveCacheSelection() throws Exception {
    Files.deleteIfExists(Path.of(
        System.getProperty("user.home"), ".nq", "config.properties"));
  }

  @Test
  void sqlToXmlScriptDoesNotReadUnusedLoadFile() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    Path script = write("query.nq", "select 1 into {result.value}\\G\n");
    Path missingLoadFile = tempDir.resolve("missing.xml");

    Main.main(script.toString(), missingLoadFile.toString(), "-p", properties.toString());
  }

  @Test
  void parseErrorsAreReportedOnceThroughLog() throws Exception {
    String message = "line 1:22 expected '\\g' or ';' at line end";

    String errors = captureStderr(() -> {
      HiqlSyntaxException exception = assertThrows(
          HiqlSyntaxException.class,
          () -> Main.main("select * from festival"));
      assertEquals(message, exception.getMessage());
    });

    assertTrue(errors.contains("ERROR - " + message));
    assertFalse(errors.contains("blater.nq.util.Log"));
    assertEquals(1, errors.split(java.util.regex.Pattern.quote(message), -1).length - 1);
  }

  @Test
  void parseErrorsUseHumanReadableKeywordNames() throws Exception {
    HiqlSyntaxException exception = assertThrows(
        HiqlSyntaxException.class,
        () -> Main.main("update table menu using (insert);"));

    assertEquals(
        "line 1:13 unexpected 'menu'; expected 'from' or 'set'",
        exception.getMessage());
  }

  @Test
  void dmlScriptLoadsXmlWhenMappedInputIsReached() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create table audit_log (personid integer, firstname varchar(80))");

      Path script = write("insert.nq", """
          autocommit on
          \\g
          insert into audit_log (personid, firstname)
          values ({message.person.id}, {message.person.firstname})
          \\g
          """);
      Path input = write("input.xml", """
          <message>
            <person>
              <id>7</id>
              <firstname>Fred</firstname>
            </person>
          </message>
          """);

      Main.main(script.toString(), input.toString(), "-p", properties.toString());

      assertEquals(1, queryInt(connection, "select count(*) from audit_log"));
      assertEquals("Fred", queryString(connection, "select firstname from audit_log where personid = 7"));
    }
  }

@Test
  void missingLoadFileFailsWhenDmlMappingNeedsXml() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create table audit_log (personid integer)");
      Path script = write("insert.nq", """
          insert into audit_log (personid)
          values ({message.person.id})
          \\g
          """);

      assertThrows(IllegalStateException.class,
          () -> Main.main(script.toString(), "-p", properties.toString()));
    }
  }

@Test
  void argumentsCanAppearInAnyUnambiguousOrder() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create table audit_log (actor varchar(80))");
      Path script = write("literal.nq", """
          autocommit on
          \\g
          insert into audit_log (actor) values ('${actor}');
          \\g
          """);

      Main.main("actor=Fred", "-p", properties.toString(), script.toString());

      assertEquals("Fred", queryString(connection, "select actor from audit_log"));
    }
  }

  @Test
  void scriptOutputDirectiveSelectsOutputWriter() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    Path script = write("query.nq", """
        output json;
        select 1 into {result.value};
        """);

    String output = captureStdout(() -> Main.main(script.toString(), "-p", properties.toString()));

    assertEquals("""
        {"result":{"value":"1"}}
        """, output);
  }

  @Test
  void commandLineOutputFlagOverridesScriptOutputDirective() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    Path script = write("query.nq", """
        output xml;
        select 1 into {result.value};
        """);

    String output = captureStdout(
        () -> Main.main(script.toString(), "-p", properties.toString(), "--output", "JSON"));

    assertEquals("""
        {"result":{"value":"1"}}
        """, output);
  }

  @Test
  void noKeyInferenceFlagPreservesOneObjectPerResultRow() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table item (id integer, label varchar(80))",
          "insert into item values (1, 'first')",
          "insert into item values (1, 'second')");
    }
    Path script = write("unstructured-query.nq", """
        output json;
        select id into {result.item.id}, label into {result.item.label}
        from item order by label;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(), "--no-key-inference"));

    assertEquals("""
        {"result":{"item":[{"id":"1","label":"first"},{"id":"1","label":"second"}]}}
        """, output);
  }

  @Test
  void infersPrimaryKeysForNestedDqlOutput() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table customer (id integer primary key, name varchar(80))",
          "create table purchase (id integer primary key, customer_id integer not null, item varchar(80), "
              + "foreign key (customer_id) references customer(id))",
          "insert into customer values (1, 'Fred')",
          "insert into customer values (2, 'Wilma')",
          "insert into purchase values (10, 1, 'Tea')",
          "insert into purchase values (11, 1, 'Cake')",
          "insert into purchase values (12, 2, 'Coffee')",
          "insert into purchase values (13, 2, 'Toast')");
    }
    Path script = write("inferred-query.nq", """
        output json;
        select
          c.id into {result.customer.id},
          c.name into {result.customer.name},
          p.id into {result.customer.purchase.id},
          p.item into {result.customer.purchase.item}
        from customer c
        join purchase p on p.customer_id = c.id
        order by p.id;
        """);

    String output = captureStdout(() -> Main.main(script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("metadata-cache").toString()));

    assertEquals("""
        {"result":[{"customer":{"id":"1","name":"Fred","purchase":[{"id":"10","item":"Tea"},{"id":"11","item":"Cake"}]}},{"customer":{"id":"2","name":"Wilma","purchase":[{"id":"12","item":"Coffee"},{"id":"13","item":"Toast"}]}}]}
        """, output);
  }

  @Test
  void mapsResultLevelsZeroThroughFive() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table festival (id integer primary key, name varchar(80))",
          "insert into festival values (1, 'First')",
          "insert into festival values (2, 'Second')");
    }
    record Shape(String query, String expected) { }
    List<Shape> shapes = List.of(
        new Shape("select name from festival order by id",
            "[{\"name\":\"First\"},{\"name\":\"Second\"}]"),
        new Shape("select name into {name} from festival order by id",
            "[{\"name\":\"First\"},{\"name\":\"Second\"}]"),
        new Shape("select name into {festival.name} from festival order by id",
            "[{\"festival\":{\"name\":\"First\"}},{\"festival\":{\"name\":\"Second\"}}]"),
        new Shape("select name into {res.festival.name} from festival order by id",
            "{\"res\":[{\"festival\":{\"name\":\"First\"}},{\"festival\":{\"name\":\"Second\"}}]}"),
        new Shape("select name into {root.res.festival.name} from festival order by id",
            "{\"root\":{\"res\":[{\"festival\":{\"name\":\"First\"}},{\"festival\":{\"name\":\"Second\"}}]}}"),
        new Shape("select name into {document.root.res.festival.name} from festival order by id",
            "{\"document\":{\"root\":{\"res\":[{\"festival\":{\"name\":\"First\"}},{\"festival\":{\"name\":\"Second\"}}]}}}"));

    for (int level = 0; level < shapes.size(); level++) {
      Shape shape = shapes.get(level);
      Path script = write("level-" + level + "-query.nq", "output json;\n" + shape.query() + ";\n");
      String output = captureStdout(() -> Main.main(script.toString(), "-p", properties.toString(),
          "--cache-dir", tempDir.resolve("level-metadata-cache").toString()));
      assertEquals(shape.expected() + "\n", output, "level " + level);
    }

    Path empty = write("empty-level-3-query.nq", """
        output json;
        select name into {res.festival.name} from festival where id < 0;
        """);
    String emptyOutput = captureStdout(() -> Main.main(empty.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("level-metadata-cache").toString()));
    assertEquals("{\"res\":[]}\n", emptyOutput);
  }

  @Test
  void debugLogsOnlyTheInferredRelationshipsUsedByTheQuery() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table customer (id integer primary key, name varchar(80))",
          "create table purchase (id integer primary key, customer_id integer not null, item varchar(80), "
              + "foreign key (customer_id) references customer(id))",
          "insert into customer values (1, 'Fred')",
          "insert into purchase values (10, 1, 'Tea')");
    }
    Path script = write("debug-inference.nq", """
        output json;
        select
          c.id into {result.customer.id},
          c.name into {result.customer.name},
          p.id into {result.customer.purchase.id},
          p.item into {result.customer.purchase.item}
        from customer c
        join purchase p on p.customer_id = c.id;
        """);
    Path cache = tempDir.resolve("debug-metadata-cache");

    String normal = captureStderr(() -> captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(), "--cache-dir", cache.toString())));
    String debug;
    try {
      debug = captureStderr(() -> captureStdout(() -> Main.main(
          script.toString(), "-p", properties.toString(), "--cache-dir", cache.toString(), "--debug")));
    } finally {
      blater.nq.util.Log.debug(false);
    }

    assertFalse(normal.contains("Inferred DQL structure relationships used"));
    assertTrue(debug.contains("Inferred DQL structure relationships used"));
    assertTrue(debug.contains("{result.customer} -> c"));
    assertTrue(debug.contains("key (ID) [PRIMARY_KEY]"));
    assertTrue(debug.contains("{result.customer.purchase} -> p"));
    assertTrue(debug.contains("[DECLARED_FOREIGN_KEY]"));
  }

  @Test
  void explicitStructureOverridesInferenceOnlyForItsExactPath() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table customer (id integer primary key, group_code varchar(20), name varchar(80))",
          "create table purchase (id integer primary key, customer_id integer not null, item varchar(80), "
              + "foreign key (customer_id) references customer(id))",
          "insert into customer values (1, 'G', 'Shared')",
          "insert into customer values (2, 'G', 'Shared')",
          "insert into purchase values (10, 1, 'Tea')",
          "insert into purchase values (11, 2, 'Cake')");
    }
    Path script = write("partial-structure.nq", """
        output json;
        select
          c.group_code into {result.customer.code},
          c.name into {result.customer.name},
          p.id into {result.customer.purchase.id},
          p.item into {result.customer.purchase.item}
        from customer c
        join purchase p on p.customer_id = c.id
        order by p.id
        structure {result.customer} key (c.group_code);
        """);

    String[] output = new String[1];
    String debug;
    try {
      debug = captureStderr(() -> output[0] = captureStdout(() -> Main.main(
          script.toString(), "-p", properties.toString(),
          "--cache-dir", tempDir.resolve("partial-cache").toString(), "--debug")));
    } finally {
      blater.nq.util.Log.debug(false);
    }

    assertEquals("""
        {"result":{"customer":[{"code":"G","name":"Shared","purchase":[{"id":"10","item":"Tea"},{"id":"11","item":"Cake"}]}]}}
        """, output[0]);
    assertTrue(debug.contains("{result.customer.purchase} -> p"));
    assertTrue(debug.contains("parent c via"));
  }

  @Test
  void explicitKeyExpressionBindsAnOtherwiseUnmappedParentForDescendantInference() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table customer (id integer primary key)",
          "create table purchase (id integer primary key, customer_id integer not null, item varchar(80), "
              + "foreign key (customer_id) references customer(id))",
          "insert into customer values (1)",
          "insert into purchase values (10, 1, 'Tea')",
          "insert into purchase values (11, 1, 'Cake')");
    }
    Path script = write("explicit-unmapped-parent.nq", """
        output json;
        select
          p.id into {result.customer.purchase.id},
          p.item into {result.customer.purchase.item}
        from customer c
        join purchase p on p.customer_id = c.id
        order by p.id
        structure {result.customer} key (c.id);
        """);
    String[] output = new String[1];
    String debug;
    try {
      debug = captureStderr(() -> output[0] = captureStdout(() -> Main.main(
          script.toString(), "-p", properties.toString(),
          "--cache-dir", tempDir.resolve("explicit-unmapped-parent-cache").toString(), "--debug")));
    } finally {
      blater.nq.util.Log.debug(false);
    }

    assertEquals("""
        {"result":{"customer":[{"purchase":[{"id":"10","item":"Tea"},{"id":"11","item":"Cake"}]}]}}
        """, output[0]);
    assertTrue(debug.contains("{result.customer.purchase} -> p"));
    assertTrue(debug.contains("parent c via"));
  }

  @Test
  void inferredKeyConflictKeepsFirstValueAndWarnsAboutPossibleDataLoss() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table item (id integer, label varchar(80))",
          "insert into item values (1, 'first')",
          "insert into item values (1, 'second')");
    }
    Path script = write("ambiguous-data.nq", """
        output json;
        select id into {result.item.id}, label into {result.item.label}
        from item order by label;
        """);
    String[] output = new String[1];

    String warnings = captureStderr(() -> output[0] = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("warning-cache").toString())));

    assertEquals("""
        {"result":[{"item":{"id":"1","label":"first"}}]}
        """, output[0]);
    assertTrue(warnings.toLowerCase().contains("possible data loss"));
    assertTrue(warnings.contains("result.item.label"));
  }

  @Test
  void metadataRefreshAndExpiryCommandsUseTheSelectedTargetAndExit() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create table item (id integer primary key)");
    }
    Path cache = tempDir.resolve("metadata-command-cache");

    String refreshed = captureStdout(() -> Main.main(
        "-p", properties.toString(), "--cache-dir", cache.toString(), "--metadata-refresh"));
    String configured = captureStdout(() -> Main.main(
        "-p", properties.toString(), "--cache-dir", cache.toString(), "--metadata-expiry-hours", "0"));

    assertTrue(refreshed.contains("Refreshed database key metadata"));
    assertTrue(configured.contains("expiry set to 0"));
    try (var files = Files.list(cache)) {
      assertEquals(1, files.filter(path -> path.getFileName().toString().endsWith(".mv.db")).count());
    }
  }

  @Test
  void staleReferencedKeyMetadataRefreshesBeforeTheNextQuery() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table item (code varchar(20), label varchar(80))",
          "create table tag (id integer primary key, item_code varchar(20))",
          "insert into item values ('A', 'Alpha')",
          "insert into tag values (1, 'A')",
          "insert into tag values (2, 'A')");
    }
    Path script = write("stale-metadata.nq", """
        output json;
        select i.code into {result.item.code}, i.label into {result.item.label}
        from item i join tag t on t.item_code = i.code order by t.id;
        """);
    Path cache = tempDir.resolve("stale-cache");

    String before = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(), "--cache-dir", cache.toString()));
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create unique index uq_item_code on item(code)");
    }
    String after = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(), "--cache-dir", cache.toString()));

    assertEquals("""
        {"result":{"item":[{"code":"A","label":"Alpha"},{"code":"A","label":"Alpha"}]}}
        """, before);
    assertEquals("""
        {"result":[{"item":{"code":"A","label":"Alpha"}}]}
        """, after);
  }

  @Test
  void inferredKeysCollapseIndependentJoinedCollectionsWithoutCartesianDuplicates() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table parent (id integer primary key, name varchar(80))",
          "create table note (id integer primary key, parent_id integer, text varchar(80))",
          "create table tag (id integer primary key, parent_id integer, text varchar(80))",
          "insert into parent values (1, 'P')",
          "insert into note values (10, 1, 'N1')",
          "insert into note values (11, 1, 'N2')",
          "insert into tag values (20, 1, 'T1')",
          "insert into tag values (21, 1, 'T2')");
    }
    Path script = write("independent-children.nq", """
        output json;
        select
          p.id into {result.parent.id}, p.name into {result.parent.name},
          n.id into {result.parent.note.id}, n.text into {result.parent.note.text},
          t.id into {result.parent.tag.id}, t.text into {result.parent.tag.text}
        from parent p
        join note n on n.parent_id = p.id
        join tag t on t.parent_id = p.id
        order by n.id, t.id;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("sibling-cache").toString()));

    assertEquals("""
        {"result":[{"parent":{"id":"1","name":"P","note":[{"id":"10","text":"N1"},{"id":"11","text":"N2"}],"tag":[{"id":"20","text":"T1"},{"id":"21","text":"T2"}]}}]}
        """, output);
  }

  @Test
  void partiallyNullInferredCompositeKeyFallsBackToRowLocalIdentity() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table item (part_a integer, part_b integer, label varchar(80), unique(part_a, part_b))",
          "insert into item values (1, null, 'first')",
          "insert into item values (1, null, 'second')");
    }
    Path script = write("partial-inferred-key.nq", """
        output json;
        select part_a into {result.item.a}, part_b into {result.item.b}, label into {result.item.label}
        from item order by label;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("partial-inferred-cache").toString()));

    assertEquals("""
        {"result":[{"item":{"a":"1","b":null,"label":"first"}},{"item":{"a":"1","b":null,"label":"second"}}]}
        """, output);
  }

  @Test
  void groupedDqlUsesTheGroupingTupleInsteadOfTableKeys() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table sale (id integer primary key, category varchar(20), amount integer)",
          "insert into sale values (1, 'A', 10)",
          "insert into sale values (2, 'A', 20)",
          "insert into sale values (3, 'B', 5)");
    }
    Path script = write("grouped-inference.nq", """
        output json;
        select category into {result.summary.category}, sum(amount) into {result.summary.total}
        from sale group by category order by category;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("grouped-cache").toString()));

    assertEquals("""
        {"result":[{"summary":{"category":"A","total":"30"}},{"summary":{"category":"B","total":"5"}}]}
        """, output);
  }

  @Test
  void groupedInferenceKeepsIndependentCustomerAndOrderIdentity() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table customers (customer_id integer primary key, customer_name varchar(80))",
          "create table orders (order_id integer primary key, customer_id integer not null, amount integer, "
              + "foreign key (customer_id) references customers(customer_id))",
          "insert into customers values (1, 'Ada')",
          "insert into customers values (2, 'Lin')",
          "insert into orders values (10, 1, 12)",
          "insert into orders values (11, 1, 8)");
    }
    Path script = write("grouped-customer-orders.nq", """
        output json;
        select
          c.customer_id into {customers.customer.id},
          c.customer_name into {customers.customer.name},
          o.order_id into {customers.customer.orders.id},
          sum(o.amount) into {customers.customer.orders.total}
        from customers c
        left join orders o on o.customer_id = c.customer_id
        group by c.customer_id, c.customer_name, o.order_id
        order by c.customer_id, o.order_id;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("grouped-customer-orders-cache").toString()));

    assertEquals("""
        {"customers":{"customer":[{"id":"1","name":"Ada","orders":[{"id":"10","total":"12"},{"id":"11","total":"8"}]},{"id":"2","name":"Lin"}]}}
        """, output);
  }

  @Test
  void quotedIdentifierBoundariesSurviveKeyInference() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table \"Customers\" (\"Customer.Id\" integer primary key, \"Display Name\" varchar(80))",
          "insert into \"Customers\" values (1, 'Ada')",
          "insert into \"Customers\" values (2, 'Lin')");
    }
    Path script = write("quoted-key-inference.nq", """
        output json;
        select
          c."Customer.Id" into {customers.customer.id},
          c."Display Name" into {customers.customer.name}
        from "Customers" c
        order by c."Customer.Id";
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("quoted-key-cache").toString()));

    assertEquals("""
        {"customers":{"customer":[{"id":"1","name":"Ada"},{"id":"2","name":"Lin"}]}}
        """, output);
  }

  @Test
  void unclassifiedFunctionPreservesRowFirstOutput() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table company (id integer primary key, name varchar(80))",
          "create table tag (id integer primary key, company_id integer not null, "
              + "foreign key (company_id) references company(id))",
          "insert into company values (1, 'Acme')",
          "insert into tag values (10, 1)",
          "insert into tag values (11, 1)");
    }
    Path script = write("unknown-function-inference.nq", """
        output json;
        select upper(c.name) into {result.company.name}
        from company c join tag t on t.company_id = c.id
        order by t.id;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("unknown-function-cache").toString()));

    assertEquals("""
        {"result":{"company":[{"name":"ACME"},{"name":"ACME"}]}}
        """, output);
  }

  @Test
  void hierarchyUnionUsesCompatibleBranchLocalInferredKeys() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection,
          "create table person (id integer primary key, name varchar(80))",
          "create table company (id integer primary key, name varchar(80))",
          "insert into person values (1, 'Fred')",
          "insert into company values (1, 'Acme')");
    }
    Path script = write("union-inference.nq", """
        output json;
        select p.id into {result.entry.id}, p.name into {result.entry.name}
        from person p
        hierarchy union
        select c.id into {result.entry.id}, c.name into {result.entry.name}
        from company c;
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), "-p", properties.toString(),
        "--cache-dir", tempDir.resolve("union-cache").toString()));

    assertEquals("""
        {"result":[{"entry":{"id":"1","name":"Fred"}},{"entry":{"id":"1","name":"Acme"}}]}
        """, output);
  }

  @Test
  void markdownOutputCanBeSelectedByScriptOrCommandLine() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    Path markdownScript = write("markdown-query.nq", """
        output markdown;
        select 1 into {result.value};
        """);
    Path overriddenScript = write("overridden-query.nq", """
        output xml;
        select 1 into {result.value};
        """);

    String scriptOutput = captureStdout(
        () -> Main.main(markdownScript.toString(), "-p", properties.toString()));
    String commandOutput = captureStdout(
        () -> Main.main(
            overriddenScript.toString(), "-p", properties.toString(), "--output", "markdown"));

    assertEquals(scriptOutput, commandOutput);
    assertEquals(3, scriptOutput.lines().count());
    assertTrue(scriptOutput.contains("1"));
  }

  @Test
  void inlineScriptArgumentCanReplaceScriptFile() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);

    String output = captureStdout(() -> Main.main(
        "output json; select 1 into {result.value};",
        "-p",
        properties.toString()));

    assertEquals("""
        {"result":{"value":"1"}}
        """, output);
  }

  @Test
  void inlineScriptArgumentCanFollowInputFile() throws Exception {
    Path input = write("input.json", """
        {
          "data": {
            "customer": [
              { "id": "C1", "country": "GB" }
            ]
          }
        }
        """);

    String output = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir",
        tempDir.resolve("cache-inline").toString(),
        input.toString(),
        "output json; catalog;"));

    assertTrue(output.contains("\"catalog\""));
    assertTrue(output.contains("\"CUSTOMER\""));
  }

  @Test
  void shortOutputFlagIsCaseInsensitive() throws Exception {
    String url = databaseUrl();
    Path properties = propertiesFile(url);
    Path script = write("query.nq", "select 1 into {result.value};");

    String output = captureStdout(
        () -> Main.main("-o", "csv", script.toString(), "-p", properties.toString()));

    assertEquals("""
        value
        1
        """, output);
  }

  @Test
  void cacheFlagCanAppearBeforeScriptAndInputFile() throws Exception {
    Path script = write("query.nq", "select 1 into {result.value};");
    Path input = write("input.json", "{}");

    var params = ParameterParser.parse(
        "--cache",
        "--cache-dir", tempDir.resolve("cache").toString(),
        script.toString(),
        input.toString());

    assertEquals("true", params.get(ParameterParser.CACHE_MODE_PARAM));
    assertEquals(tempDir.resolve("cache").toString(), params.get(ParameterParser.CACHE_DIR_PARAM));
    assertEquals(script.toString(), params.get(ParameterParser.SCRIPT_FILE_PARAM));
    assertEquals(input.toString(), params.get(ParameterParser.INPUT_FILENAME));
  }

  @Test
  void cacheFlagWithOneInputFileIsAStandaloneCacheCommand() throws Exception {
    Path input = write("standalone.json", "{}");

    var params = ParameterParser.parse("--cache", input.toString());

    assertEquals("true", params.get(ParameterParser.CACHE_MODE_PARAM));
    assertEquals(input.toString(), params.get(ParameterParser.INPUT_FILENAME));
    assertFalse(params.containsKey(ParameterParser.SCRIPT_FILE_PARAM));
    assertFalse(params.containsKey(ParameterParser.SCRIPT_TEXT_PARAM));
  }

  @Test
  void shortCacheFlagSelectsStandaloneCacheCommand() throws Exception {
    Path input = write("standalone.json", "{}");

    var params = ParameterParser.parse("-c", input.toString());

    assertEquals("true", params.get(ParameterParser.CACHE_MODE_PARAM));
    assertEquals(input.toString(), params.get(ParameterParser.INPUT_FILENAME));
  }

  @Test
  void inputFileAloneSelectsDirectConversion() {
    for (String extension : List.of("xml", "csv", "json", "yaml", "yml", "parquet")) {
      String input = "standalone." + extension;

      var params = ParameterParser.parse(input);

      assertFalse(params.containsKey(ParameterParser.CACHE_MODE_PARAM));
      assertEquals(input, params.get(ParameterParser.INPUT_FILENAME));
      assertFalse(params.containsKey(ParameterParser.SCRIPT_FILE_PARAM));
      assertFalse(params.containsKey(ParameterParser.SCRIPT_TEXT_PARAM));
    }
  }

  @Test
  void standaloneCacheRejectsOutputAndDirectConversionRejectsCacheDirectory() throws Exception {
    Path input = write("standalone.json", "{}");

    IllegalArgumentException outputProblem = assertThrows(
        IllegalArgumentException.class,
        () -> ParameterParser.parse("--cache", input.toString(), "--output", "yaml"));
    IllegalArgumentException directoryProblem = assertThrows(
        IllegalArgumentException.class,
        () -> ParameterParser.parse(input.toString(), "--cache-dir", tempDir.toString()));

    assertEquals("--output is not valid when loading a cache without a script.", outputProblem.getMessage());
    assertEquals("--cache-dir requires --cache when no script is supplied.", directoryProblem.getMessage());
  }

  @Test
  void inlineQueryUsesEphemeralCacheWithoutExplicitCacheFlag() throws Exception {
    String query = "select id from item where a in (select max(a) from item);";
    Path input = write("elements.json", """
        [
          {"a": 1, "id": 1},
          {"a": 2, "id": 2},
          {"a": 2, "id": 3},
          {"a": 1, "id": 4}
        ]
        """);
    Path cacheDir = tempDir.resolve("implicit-file-cache");
    var params = ParameterParser.parse(query, input.toString());

    assertFalse(params.containsKey(ParameterParser.CACHE_MODE_PARAM));
    assertTrue(CacheExecution.usesEphemeralCache(params));

    String[] output = new String[1];
    String normalLog = captureStderr(() -> output[0] = captureStdout(() -> Main.main(
        query,
        input.toString(),
        "--cache-dir", cacheDir.toString())));

    assertEquals("""
        [{"id":"2"},{"id":"3"}]
        """, output[0]);
    assertEquals("", normalLog);
    assertFalse(Files.exists(cacheDir));

    Files.writeString(input, """
        [
          {"a": 4, "id": 4},
          {"a": 3, "id": 5}
        ]
        """);
    String refreshed = captureStdout(() -> Main.main(
        query,
        input.toString(),
        "--cache-dir", cacheDir.toString()));
    assertEquals("""
        [{"id":"4"}]
        """, refreshed);

    String debugLog;
    try {
      debugLog = captureStderr(() -> captureStdout(() -> Main.main(
          query,
          input.toString(),
          "--cache-dir", tempDir.resolve("debug-file-cache").toString(),
          "--debug")));
    } finally {
      blater.nq.util.Log.debug(false);
    }
    assertTrue(debugLog.contains("DEBUG: Trace SQL: create table item"));
    assertTrue(debugLog.contains("DEBUG: Cache table [item]"));

    Path mappedCacheDir = tempDir.resolve("mapped-ephemeral-cache");
    captureStdout(() -> Main.main(
        "select id into {result.item.id} from item;",
        input.toString(),
        "--cache-dir", mappedCacheDir.toString()));
    assertFalse(Files.exists(mappedCacheDir));
  }

  @Test
  void inlineQueryReadsTypedJsonFromStandardInput() throws Exception {
    String input = """
        {
          "users": [
            {"name": "Alice", "active": true},
            {"name": "Bob", "active": false},
            {"name": "Charlie", "active": true}
          ]
        }
        """;

    String output = withStandardInput(input, () -> captureStdout(() -> Main.main(
        "-i", "json",
        "select name from users where active = 'true' order by id;")));

    assertEquals("""
        [{"name":"Alice"},{"name":"Charlie"}]
        """, output);
  }

  @Test
  void longInputOptionSelectsStandardInputAndAllowsPersistentCache() {
    var params = ParameterParser.parse(
        "--input=json", "--cache", "--relation-alias", "/users=people",
        "select name from people;");

    assertEquals("json", params.get(ParameterParser.INPUT_TYPE_PARAM));
    assertEquals(ParameterParser.STDIN_INPUT, params.get(ParameterParser.INPUT_FILENAME));
    assertEquals("select name from people;", params.get(ParameterParser.SCRIPT_TEXT_PARAM));
    assertEquals("true", params.get(ParameterParser.CACHE_MODE_PARAM));
  }

  @Test
  void persistentStandardInputCacheCanBeReusedAsTheActiveCache() throws Exception {
    String input = """
        {"users":[{"name":"Alice","active":true},{"name":"Bob","active":false}]}
        """;
    String query = "select name from users where active = 'true' order by id;";
    Path cacheDir = tempDir.resolve("stdin-cache");

    String first = withStandardInput(input, () -> captureStdout(() -> Main.main(
        "-i", "json", "--cache", "--cache-dir", cacheDir.toString(), query)));
    String sameStream = withStandardInput(input, () -> captureStdout(() -> Main.main(
        "-i", "json", "--cache", "--cache-dir", cacheDir.toString(), query)));
    String reused = captureStdout(() -> Main.main(query));

    assertEquals("""
        [{"name":"Alice"}]
        """, first);
    assertEquals(first, sameStream);
    assertEquals(first, reused);
    try (var cacheFiles = Files.list(cacheDir)) {
      assertEquals(1, cacheFiles
          .filter(path -> path.getFileName().toString().endsWith(".mv.db"))
          .count());
    }
  }

  @Test
  void catalogCommandStoresItsOptionalPatternAndConnectionSelection() {
    var summary = ParameterParser.parse("catalog");
    var details = ParameterParser.parse("--output=json", "catalog", "customer*");
    var cache = ParameterParser.parse(
        "catalog", "customer*", "--cache", "customers.json", "--output", "json");
    var jdbc = ParameterParser.parse("catalog", "*", "--db", "h2", "--database", "mem:catalog");

    assertEquals("", summary.get(ParameterParser.CATALOG_PATTERN_PARAM));
    assertFalse(summary.containsKey(ParameterParser.SCRIPT_FILE_PARAM));
    assertFalse(summary.containsKey(ParameterParser.SCRIPT_TEXT_PARAM));
    assertEquals("customer*", details.get(ParameterParser.CATALOG_PATTERN_PARAM));
    assertEquals("json", details.get(ParameterParser.OUTPUT_TYPE_PARAM));
    assertEquals("customer*", cache.get(ParameterParser.CATALOG_PATTERN_PARAM));
    assertEquals("customers.json", cache.get(ParameterParser.INPUT_FILENAME));
    assertEquals("json", cache.get(ParameterParser.OUTPUT_TYPE_PARAM));
    assertEquals("*", jdbc.get(ParameterParser.CATALOG_PATTERN_PARAM));
    assertEquals("jdbc:h2:mem:catalog", jdbc.get(ParameterParser.JDBC_DATABASE_PARAM));
  }

  @Test
  void cacheClearFlagsDoNotRequireScriptFile() {
    var all = ParameterParser.parse("--clear-cache", "--cache-dir", tempDir.resolve("cache").toString());
    var target = ParameterParser.parse("--clear-cache", "input.json", "--cache-dir", tempDir.resolve("cache").toString());
    var older = ParameterParser.parse("--clear-cache-older-than", "30m", "--cache-dir", tempDir.resolve("cache").toString());
    var list = ParameterParser.parse("--list-caches", "--cache-dir", tempDir.resolve("cache").toString());
    var use = ParameterParser.parse("--use-cache=input.json", "--cache-dir", tempDir.resolve("cache").toString());

    assertEquals("true", all.get(ParameterParser.CACHE_CLEAR_ALL_PARAM));
    assertEquals("input.json", target.get(ParameterParser.CACHE_CLEAR_TARGET_PARAM));
    assertEquals("30m", older.get(ParameterParser.CACHE_CLEAR_OLDER_THAN_PARAM));
    assertEquals("true", list.get(ParameterParser.CACHE_LIST_PARAM));
    assertEquals("input.json", use.get(ParameterParser.CACHE_USE_PARAM));
  }

  @Test
  void useCacheRequiresASourceAndRejectsOtherPositionals() {
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("--use-cache"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ParameterParser.parse("--use-cache", "input.json", "query.nq"));
  }

  @Test
  void parquetNamingFlagsAreStoredAsSystemParameters() throws Exception {
    Path script = write("query.nq", "catalog;");
    Path input = write("input.parquet", "");

    var params = ParameterParser.parse(
        "--parquet-root", "customers",
        "--parquet-record=customer",
        script.toString(),
        input.toString());

    assertEquals("customers", params.get(ParameterParser.PARQUET_ROOT_PARAM));
    assertEquals("customer", params.get(ParameterParser.PARQUET_RECORD_PARAM));
    assertEquals(script.toString(), params.get(ParameterParser.SCRIPT_FILE_PARAM));
    assertEquals(input.toString(), params.get(ParameterParser.INPUT_FILENAME));
  }

  @Test
  void parquetNamingFlagsSupportEqualsAndRejectMissingValues() throws Exception {
    Path script = write("query.nq", "catalog;");
    Path input = write("input.parquet", "");

    var params = ParameterParser.parse(
        "--parquet-root=customers",
        "--parquet-record=customer",
        script.toString(),
        input.toString());

    assertEquals("customers", params.get(ParameterParser.PARQUET_ROOT_PARAM));
    assertEquals("customer", params.get(ParameterParser.PARQUET_RECORD_PARAM));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("--parquet-root"));
    assertThrows(IllegalArgumentException.class, () -> ParameterParser.parse("--parquet-record", "--cache"));
  }

  @Test
  void nonJdbcLongOptionsShareEqualsValueSyntax() throws Exception {
    Path script = write("query.nq", "catalog;");

    var params = ParameterParser.parse(
        script.toString(),
        "--output=json",
        "--cache-dir=" + tempDir.resolve("cache"),
        "--parquet-root=customers",
        "--parquet-record=customer");
    var clearTarget = ParameterParser.parse("--clear-cache=input.json");
    var clearOlder = ParameterParser.parse("--clear-cache-older-than=6h");

    assertEquals("json", params.get(ParameterParser.OUTPUT_TYPE_PARAM));
    assertEquals(tempDir.resolve("cache").toString(), params.get(ParameterParser.CACHE_DIR_PARAM));
    assertEquals("customers", params.get(ParameterParser.PARQUET_ROOT_PARAM));
    assertEquals("customer", params.get(ParameterParser.PARQUET_RECORD_PARAM));
    assertEquals("input.json", clearTarget.get(ParameterParser.CACHE_CLEAR_TARGET_PARAM));
    assertEquals("6h", clearOlder.get(ParameterParser.CACHE_CLEAR_OLDER_THAN_PARAM));
  }

  @Test
  void propertiesFileCannotSetParquetSystemParameters() {
    Map<String, String> params = new LinkedHashMap<>();

    ParameterParser.addParameterFromMainPropsFile(
        params,
        ParameterParser.PARQUET_ROOT_PARAM + "=customers");
    ParameterParser.addParameterFromMainPropsFile(
        params,
        ParameterParser.PARQUET_RECORD_PARAM + "=customer");

    assertEquals(Map.of(), params);
  }

  @Test
  void cacheModeQueriesJsonInputThroughGeneratedTables() throws Exception {
    Path script = write("query.nq", """
        output json;
        select
          c.id into {result.customer.id},
          c.country into {result.customer.country}
        from customer c
        where c.country = 'GB'
        order by c.id asc createsNew {result.customer};
        """);
    Path input = write("input.json", """
        {
          "data": {
            "customer": [
              { "id": "C1", "country": "GB" },
              { "id": "C2", "country": "US" }
            ]
          }
        }
        """);

    String output = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", tempDir.resolve("cache-json").toString(),
        script.toString(),
        input.toString()));

    assertEquals("""
        {"result":{"customer":[{"id":"C1","country":"GB"}]}}
        """, output);
  }

  @Test
  void ephemeralQueryUsesNamedRootCollectionWithoutItemFallback() throws Exception {
    Path input = write("named-customers.json", """
        {"customers":[
          {"id":1,"name":"Alice","city":"London"},
          {"id":2,"name":"Bob","city":"Paris"},
          {"id":3,"name":"Eva","city":"London"}
        ]}
        """);

    String output = captureStdout(() -> Main.main(
        "select id, name from customers where city = 'London' order by id;",
        input.toString()));

    assertEquals("""
        [{"id":"1","name":"Alice"},{"id":"3","name":"Eva"}]
        """, output);
  }

  @Test
  void standaloneCacheLoadReportsReuseAndSuppliesTheActiveCache() throws Exception {
    Path cacheDir = tempDir.resolve("active-cache");
    Path input = write("active.json", """
        {
          "data": {
            "customer": [
              { "id": "C1", "country": "GB" }
            ]
          }
        }
        """);
    Path script = write("active-query.nq", """
        output json;
        select c.id into {result.customer.id}
        from customer c
        where c.country = 'GB';
        """);

    String loaded = captureStdout(() -> Main.main(
        "--cache", "--cache-dir", cacheDir.toString(), input.toString()));
    String reused = captureStdout(() -> Main.main(
        "--cache", "--cache-dir", cacheDir.toString(), input.toString()));
    String queryOutput = captureStdout(() -> Main.main(script.toString()));

    String source = input.toAbsolutePath().normalize().toString();
    assertEquals("Loaded cache for " + source + System.lineSeparator(), loaded);
    assertEquals("Using existing cache for " + source + System.lineSeparator(), reused);
    assertEquals("""
        {"result":[{"customer":{"id":"C1"}}]}
        """, queryOutput);
  }

  @Test
  void useCacheSwitchesToAnExistingCacheWithoutReadingItsSource() throws Exception {
    Path cacheDir = tempDir.resolve("use-cache");
    Path first = write("first-use.json", """
        { "data": { "customer": [{ "id": "FIRST" }] } }
        """);
    Path second = write("second-use.json", """
        { "data": { "customer": [{ "id": "SECOND" }] } }
        """);
    Path script = write("use-cache-query.nq", """
        output json;
        select c.id into {result.id} from customer c;
        """);

    Main.main("--cache-dir", cacheDir.toString(), "--cache", first.toString());
    Main.main("--cache-dir", cacheDir.toString(), "--cache", second.toString());
    Files.delete(first);

    String switched = captureStdout(() -> Main.main(
        "--use-cache", first.toString(), "--cache-dir", cacheDir.toString()));
    String output = captureStdout(() -> Main.main(script.toString()));

    assertEquals(
        "Active cache set to " + first.toAbsolutePath().normalize() + System.lineSeparator(),
        switched);
    assertEquals("""
        [{"result":{"id":"FIRST"}}]
        """, output);
  }

  @Test
  void useCacheDoesNotCreateAMissingCache() {
    Path cacheDir = tempDir.resolve("missing-use-cache");
    Path source = tempDir.resolve("not-cached.json");

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> Main.main(
            "--use-cache", source.toString(),
            "--cache-dir", cacheDir.toString()));

    assertEquals(
        "No existing cache found for " + source.toAbsolutePath().normalize() + ".",
        exception.getMessage());
    assertFalse(Files.exists(cacheDir));
  }

  @Test
  void explicitCacheSelectionBecomesActiveForLaterQueries() throws Exception {
    Path cacheDir = tempDir.resolve("selected-cache");
    Path first = write("first-active.json", """
        { "data": { "customer": [{ "id": "FIRST" }] } }
        """);
    Path second = write("second-active.json", """
        { "data": { "customer": [{ "id": "SECOND" }] } }
        """);
    Path script = write("selected-query.nq", """
        output json;
        select c.id into {result.id} from customer c;
        """);

    Main.main("--cache-dir", cacheDir.toString(), "--cache", first.toString());
    Main.main("--cache-dir", cacheDir.toString(), "--cache", second.toString());

    String selected = captureStdout(() -> Main.main(
        script.toString(), "--cache-dir", cacheDir.toString(), "--cache", first.toString()));
    String active = captureStdout(() -> Main.main(script.toString()));

    assertEquals("""
        [{"result":{"id":"FIRST"}}]
        """, selected);
    assertEquals(selected, active);
  }

  @Test
  void explicitCacheWinsOverJdbcSettingsAndKeepsRuntimeProperties() throws Exception {
    Path cacheDir = tempDir.resolve("cache-wins");
    Path input = write("cache-wins.json", """
        { "data": { "customer": [{ "id": "C1", "country": "GB" }] } }
        """);
    Path script = write("cache-wins.nq", """
        output json;
        select c.id into {result.id}
        from customer c
        where c.country = '${region}';
        """);
    Path properties = write("external.properties", """
        jdbc.driver=postgresql
        jdbc.database=jdbc:postgresql://invalid/external
        jdbc.username=external
        jdbc.password=secret
        region=GB
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(), input.toString(), "--cache",
        "--cache-dir", cacheDir.toString(), "-p", properties.toString()));

    assertEquals("""
        [{"result":{"id":"C1"}}]
        """, output);
  }

  @Test
  void jdbcSettingsWinOverTheActiveCacheWhenCacheIsNotExplicit() throws Exception {
    Path cacheDir = tempDir.resolve("jdbc-wins");
    Path input = write("jdbc-wins.json", """
        { "data": { "customer": [{ "id": "CACHED" }] } }
        """);
    Main.main("--cache-dir", cacheDir.toString(), "--cache", input.toString());

    String url = databaseUrl();
    try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
      execute(connection, "create table source (result_value varchar(20))");
      execute(connection, "insert into source (result_value) values ('EXTERNAL')");
    }
    Path properties = propertiesFile(url);
    Path script = write("jdbc-wins.nq", """
        output json;
        select result_value into {result.value} from source;
        """);

    String output = captureStdout(() -> Main.main(script.toString(), "-p", properties.toString()));

    assertEquals("""
        {"result":{"value":"EXTERNAL"}}
        """, output);
  }

  @Test
  void cacheModeReusesExistingCacheWithoutReadingSourceFile() throws Exception {
    Path cacheDir = tempDir.resolve("cache-reuse");
    Path script = write("query.nq", """
        output json;
        select c.id into {result.customer.id}
        from customer c
        where c.country = 'GB';
        """);
    Path input = write("input.json", """
        {
          "data": {
            "customer": [
              { "id": "C1", "country": "GB" },
              { "id": "C2", "country": "US" }
            ]
          }
        }
        """);

    String firstOutput = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", cacheDir.toString(),
        script.toString(),
        input.toString()));
    assertEquals("""
        {"result":[{"customer":{"id":"C1"}}]}
        """, firstOutput);
    Files.delete(input);

    String output = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", cacheDir.toString(),
        script.toString(),
        input.toString()));

    assertEquals("""
        {"result":[{"customer":{"id":"C1"}}]}
        """, output);
  }

  @Test
  void cacheModeReusesExistingParquetCacheWithoutReadingSourceFile() throws Exception {
    Path cacheDir = tempDir.resolve("cache-parquet-reuse");
    Path script = write("query.nq", """
        output json;
        select c.id into {result.customer.id}
        from customer c;
        """);
    MessageType schema = ParquetTestFiles.schema("""
        message customer {
          required binary id (STRING);
        }
        """);
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Path input = tempDir.resolve("input.parquet");
    ParquetTestFiles.write(input, schema, factory.newGroup().append("id", "C1"));

    String firstOutput = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", cacheDir.toString(),
        script.toString(),
        input.toString()));
    assertEquals("""
        {"result":[{"customer":{"id":"C1"}}]}
        """, firstOutput);
    Files.delete(input);

    String output = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", cacheDir.toString(),
        script.toString(),
        input.toString()));

    assertEquals("""
        {"result":[{"customer":{"id":"C1"}}]}
        """, output);
  }

  @Test
  void cacheModeSupportsNaturalKeyJoinsOverInputStructureTables() throws Exception {
    Path script = write("query.nq", """
        output json;
        select cn.name into {result.countryName}
        from customer cu
        inner join country cn on cn.ccode = cu.ccode
        where cu.id = 'C1';
        """);
    Path input = write("input.json", """
        {
          "data": {
            "customer": [
              {
                "id": "C1",
                "ccode": "90",
                "wallet": [
                  { "symbol": "GBP", "balance": "1.93" },
                  { "symbol": "AUD", "balance": "998.33" }
                ]
              },
              {
                "id": "C2",
                "ccode": "90",
                "wallet": [
                  { "symbol": "GBP", "balance": "89933.00" }
                ]
              }
            ],
            "country": [
              { "ccode": "89", "name": "vietnam" },
              { "ccode": "90", "name": "vatican city" }
            ]
          }
        }
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(),
        input.toString(),
        "--cache",
        "--cache-dir", tempDir.resolve("cache-nested").toString()));

    assertEquals("""
        [{"result":{"countryName":"vatican city"}}]
        """, output);
  }

  @Test
  void cacheModeSupportsContainmentJoinsOverGeneratedRelationshipColumns() throws Exception {
    Path script = write("query.nq", """
        output json;
        select w.balance into {result.balance}
        from customer cu
        inner join wallet w on w.customer_id = cu.id
        where cu.id = 'C1'
          and w.symbol = 'AUD';
        """);
    Path input = write("input.json", """
        {
          "data": {
            "customer": [
              {
                "id": "C1",
                "wallet": [
                  { "symbol": "GBP", "balance": "1.93" },
                  { "symbol": "AUD", "balance": "998.33" }
                ]
              },
              {
                "id": "C2",
                "wallet": [
                  { "symbol": "GBP", "balance": "89933.00" }
                ]
              }
            ]
          }
        }
        """);

    String output = captureStdout(() -> Main.main(
        script.toString(),
        input.toString(),
        "--cache",
        "--cache-dir", tempDir.resolve("cache-contained").toString()));

    assertEquals("""
        [{"result":{"balance":"998.33"}}]
        """, output);
  }

  @Test
  void cacheModeMapsXmlAttributesToStructureColumns() throws Exception {
    Path script = write("query.nq", """
        output json;
        select
          id into {result.item.id},
          name into {result.item.name}
        from item
        where id = '7';
        """);
    Path input = write("input.xml", """
        <root>
          <item id="7"><name>Fred</name></item>
          <item id="8"><name>Wilma</name></item>
        </root>
        """);

    String output = captureStdout(() -> Main.main(
        "--cache",
        "--cache-dir", tempDir.resolve("cache-xml").toString(),
        script.toString(),
        input.toString()));

    assertEquals("""
        {"result":[{"item":{"id":"7","name":"Fred"}}]}
        """, output);
  }

  @Test
  void cacheModeWithoutInputRequiresAnActiveCache() throws Exception {
    Path script = write("query.nq", "select 1 into {result.value};");

    assertThrows(IllegalStateException.class,
        () -> Main.main(script.toString(), "--cache", "--cache-dir", tempDir.resolve("cache-missing").toString()));
  }

  @Test
  void unknownFlagFails() {
    assertThrows(Exception.class,
        () -> Main.main("-out", "result.xml"));
  }

  @Test
  void thirdPositionalArgumentFails() throws Exception {
    Path properties = propertiesFile(databaseUrl());
    Path script = write("script.nq", "select 1 as ONE\\G\n");
    Path firstLoadFile = write("first.xml", "<root/>");
    Path secondLoadFile = write("second.xml", "<root/>");

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> Main.main(
            script.toString(),
            firstLoadFile.toString(),
            secondLoadFile.toString(),
            "-p",
            properties.toString()));

    assertEquals("Unexpected argument: " + secondLoadFile, thrown.getMessage());
  }

  private String databaseUrl() {
    return "jdbc:h2:mem:hiql_" + UUID.randomUUID().toString().replace("-", "")
        + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
  }

  private Path propertiesFile(String databaseUrl) throws Exception {
    return write("hiql.properties", """
        jdbc.class.name=org.h2.Driver
        jdbc.database=%s
        jdbc.username=sa
        jdbc.password=
        """.formatted(databaseUrl));
  }

  private Path write(String name, String content) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, content);
    return path;
  }

  private void execute(Connection connection, String... sqlStatements) throws Exception {
    try (Statement statement = connection.createStatement()) {
      for (String sql : sqlStatements) {
        statement.execute(sql);
      }
    }
  }

  @Test
  void queriesJsonLinesInputAndWritesJsonLinesOutput() throws Exception {
    String query = "select id from item where a in (select max(a) from item) order by id;";
    Path input = write("elements.jsonl", """
        {"a": 1, "id": 1}
        {"a": 2, "id": 2}

        {"a": 2, "id": 3}
        """);

    String output = captureStdout(() -> Main.main(
        query,
        input.toString(),
        "--output", "jsonl",
        "--cache-dir", tempDir.resolve("jsonl-cache").toString()));

    assertEquals("""
        {"id":"2"}
        {"id":"3"}
        """, output);
  }

  private int queryInt(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
         var resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private String queryString(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
         var resultSet = statement.executeQuery(sql)) {
      if (!resultSet.next()) {
        return null;
      }
      return resultSet.getString(1);
    }
  }

  private String captureStdout(ThrowingRunnable runnable) throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      runnable.run();
    } finally {
      System.setOut(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private String captureStderr(ThrowingRunnable runnable) throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      runnable.run();
    } finally {
      System.setErr(original);
    }
    return output.toString(StandardCharsets.UTF_8);
  }

  private <T> T withStandardInput(String input, ThrowingSupplier<T> supplier) throws Exception {
    InputStream original = System.in;
    try (InputStream replacement = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))) {
      System.setIn(replacement);
      return supplier.get();
    } finally {
      System.setIn(original);
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }
}
