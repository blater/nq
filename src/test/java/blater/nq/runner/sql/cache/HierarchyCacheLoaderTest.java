package blater.nq.runner.sql.cache;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.inputreader.CsvInputReader;
import blater.nq.inputreader.ParquetInputReader;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.testsupport.H2Database;
import blater.nq.testsupport.ParquetTestFiles;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HierarchyCacheLoaderTest {
  @TempDir
  Path tempDir;

  @Test
  void createsStructureTablesFromObjectNames() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerCountryHierarchy());

        assertEquals(2, database.queryInt("select count(*) from customer"));
        assertEquals(3, database.queryInt("select count(*) from wallet"));
        assertEquals(2, database.queryInt("select count(*) from country"));
        assertEquals("90", database.queryString("select ccode from customer where id = 'C1'"));
        assertEquals("vatican city", database.queryString("select name from country where ccode = '90'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void supportsNaturalKeyJoinFromInputFields() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerCountryHierarchy());

        String countryName = database.queryString("""
            select cn.name
            from customer cu
            inner join country cn on cn.ccode = cu.ccode
            where cu.id = 'C1'
            """);

        assertEquals("vatican city", countryName);
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void addsContainmentReferenceForNestedObjects() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerCountryHierarchy());

        String balance = database.queryString("""
            select w.balance
            from customer cu
            inner join wallet w on w.customer_id = cu.id
            where cu.id = 'C1'
              and w.symbol = 'AUD'
            """);

        assertEquals("998.33", balance);
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void generatesIdWhenInputIdIsMissing() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerWithoutIdHierarchy());

        String customerId = database.queryString("select id from customer");
        String walletCustomerId = database.queryString("select customer_id from wallet where symbol = 'GBP'");

        assertNotNull(customerId);
        assertEquals(customerId, walletCustomerId);
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void preservesInputIdWhenPresent() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerHierarchy());

        assertEquals("C1", database.queryString("select id from customer where country = 'GB'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void doesNotOverwriteExistingParentIdField() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(walletWithInputParentReferenceHierarchy());

        assertEquals("EXTERNAL-CUSTOMER", database.queryString("select customer_id from wallet where symbol = 'GBP'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void addsColumnsDiscoveredAfterEarlierRows() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(customerRegionDiscoveredLateHierarchy());

        assertEquals(1, columnCount(database, "customer", "region"));
        assertEquals(1, database.queryInt("select count(*) from customer where id = 'C1' and region is null"));
        assertEquals("EMEA", database.queryString("select region from customer where id = 'C2'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void createsChildTableForRepeatedScalarValuesInsteadOfPacking() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(repeatedScalarHierarchy());

        assertEquals(0, columnCount(database, "customer", "tag"));
        assertEquals(2, database.queryInt("select count(*) from customer_tag where customer_id = 'C1'"));
        assertEquals(1, database.queryInt("select count(*) from customer_tag where value = 'vip|active'"));
        assertEquals(0, database.queryInt("select count(*) from customer_tag where value = 'vip|active|plain'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void collectionItemScalarUsesValueTableEvenWhenFirstRowHasOneValue() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(collectionItemScalarHierarchy());

        assertEquals(0, columnCount(database, "customer", "tag"));
        assertEquals(3, database.queryInt("select count(*) from customer_tag"));
        assertEquals(1, database.queryInt("""
            select count(*)
            from customer_tag
            where customer_id = 'C1'
              and value = 'active'
            """));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void mixedScalarThenCollectionShapePromotesAndBackfills() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(mixedScalarThenCollectionHierarchy());

        assertEquals(0, columnCount(database, "customer", "tag"));
        assertEquals(3, database.queryInt("select count(*) from customer_tag"));
        assertEquals(1, database.queryInt("""
            select count(*)
            from customer_tag
            where customer_id = 'C1'
              and value = 'vip'
            """));
        assertEquals(1, database.queryInt("""
            select count(*)
            from customer_tag
            where customer_id = 'C2'
              and value = 'trial'
            """));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void csvCellValuesRemainScalarAndRepeatedHeadersDoNotImplyValueTables() throws Exception {
    Path input = tempDir.resolve("customers.csv");
    Files.writeString(input, """
        customer.id,customer.tag
        C1,vip|active
        C2,plain
        """, StandardCharsets.UTF_8);

    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
        new HierarchyCacheLoader(executor).load(hierarchy);

        assertEquals(1, columnCount(database, "customer", "tag"));
        assertEquals("vip|active", database.queryString("select tag from customer where id = 'C1'"));
        assertEquals(0, tableCount(database, "customer_tag"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void csvRecordsMaterializeAsItemTable() throws Exception {
    Path input = tempDir.resolve("operations.csv");
    Files.writeString(input, """
        service,operation
        accounts,lookup
        """, StandardCharsets.UTF_8);

    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
        new HierarchyCacheLoader(executor).load(hierarchy);

        assertEquals("accounts", database.queryString("select service from item"));
        assertEquals(0, tableCount(database, "row"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void parquetDomainShapeCreatesNaturalCacheTables() throws Exception {
    MessageType schema = ParquetTestFiles.schema("""
        message customer {
          required binary id (STRING);
          optional group profile {
            optional int32 score;
            optional binary band (STRING);
          }
          optional group attributes (MAP) {
            repeated group key_value {
              required binary key (STRING);
              optional binary value (STRING);
            }
          }
          optional group tagmap (MAP) {
            repeated group key_value {
              required binary key (STRING);
              optional group value (LIST) {
                repeated group list {
                  optional binary element (STRING);
                }
              }
            }
          }
        }
        """);
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Group customer = factory.newGroup().append("id", "C1");
    customer.addGroup("profile")
        .append("score", 98)
        .append("band", "A");
    Group attributes = customer.addGroup("attributes");
    attributes.addGroup("key_value")
        .append("key", "risk")
        .append("value", "low");
    attributes.addGroup("key_value")
        .append("key", "tier")
        .append("value", "gold");
    Group tagmap = customer.addGroup("tagmap");
    Group flags = tagmap.addGroup("key_value")
        .append("key", "flags")
        .addGroup("value");
    flags.addGroup("list").append("element", "vip");
    flags.addGroup("list").append("element", "review");

    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, customer);

    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        Hierarchy hierarchy = new ParquetInputReader().load(input.toString(), Map.of());
        new HierarchyCacheLoader(executor).load(hierarchy);

        assertEquals("low", database.queryString("select risk from customer where id = 'C1'"));
        assertEquals("gold", database.queryString("select tier from customer where id = 'C1'"));
        assertEquals("98", database.queryString("select profile_score from customer where id = 'C1'"));
        assertEquals(0, tableCount(database, "attributes"));
        assertEquals(0, columnCount(database, "customer", "flags"));
        assertEquals(2, database.queryInt("select count(*) from customer_flags where customer_id = 'C1'"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void quotesUnusualIdentifiersAndStoresSqlTextAsValues() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(unusualIdentifierHierarchy());

        assertEquals(
            "select * from customer; drop table customer;",
            database.queryString("select \"select\" from \"customer detail\""));
        assertEquals(1, database.queryInt("select count(*) from \"customer detail\""));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void quotesOffsetColumnBecauseItIsAnH2Keyword() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        Node data = new Node("data");
        Node positive = new Node("positive");
        positive.addNode(value("offset", "1"));
        data.addNode(positive);

        new HierarchyCacheLoader(executor).load(new Hierarchy(data));

        assertEquals("1", database.queryString("select \"offset\" from positive"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void renderedIdentifierCollisionsFailClearly() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> new HierarchyCacheLoader(executor).load(identifierCollisionHierarchy()));

        assertEquals(
            "Cache table SQL identifier collision between [customer] and [CUSTOMER]",
            thrown.getMessage());
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void sameNamedNestedObjectsShareOneStructureTableWithParentReferences() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(duplicateWalletHierarchy());

        assertEquals(4, database.queryInt("select count(*) from wallet"));
        assertEquals(2, database.queryInt("select count(*) from wallet where customer_id is not null"));
        assertEquals(2, database.queryInt("select count(*) from wallet where account_id is not null"));
      } finally {
        executor.close();
      }
    }
  }

  @Test
  void emptyHierarchyCreatesNoTables() throws Exception {
    try (H2Database database = new H2Database()) {
      SqlExecutor executor = new SqlExecutor(database.jdbcProperties());
      try {
        new HierarchyCacheLoader(executor).load(new Hierarchy());

        assertEquals(0, database.queryInt("select count(*) from information_schema.tables where table_schema = 'PUBLIC'"));
      } finally {
        executor.close();
      }
    }
  }

  private Hierarchy customerHierarchy() {
    Node data = new Node("data");
    data.addNode(customer("C1", "GB", "INV", wallet("GBP", "10.50"), wallet("USD", "2.00")));
    data.addNode(customer("C2", "US", "INV", wallet("GBP", "4.00")));
    return new Hierarchy(data);
  }

  private Hierarchy customerCountryHierarchy() {
    Node data = new Node("data");
    data.addNode(customerWithCode("C1", "90", wallet("GBP", "1.93"), wallet("AUD", "998.33")));
    data.addNode(customerWithCode("C2", "90", wallet("GBP", "89933.00")));
    data.addNode(country("89", "vietnam"));
    data.addNode(country("90", "vatican city"));
    return new Hierarchy(data);
  }

  private Hierarchy customerRegionDiscoveredLateHierarchy() {
    Node data = new Node("data");
    Node first = new Node("customer");
    first.addNode(value("id", "C1"));
    Node second = new Node("customer");
    second.addNode(value("id", "C2"));
    second.addNode(value("region", "EMEA"));
    data.addNode(first);
    data.addNode(second);
    return new Hierarchy(data);
  }

  private Hierarchy duplicateWalletHierarchy() {
    Node data = new Node("data");
    Node customerOne = new Node("customer");
    customerOne.addNode(wallet("GBP", "1.00"));
    Node customerTwo = new Node("customer");
    customerTwo.addNode(wallet("GBP", "2.00"));
    Node accountOne = new Node("account");
    accountOne.addNode(wallet("GBP", "3.00"));
    Node accountTwo = new Node("account");
    accountTwo.addNode(wallet("GBP", "4.00"));
    data.addNode(customerOne);
    data.addNode(customerTwo);
    data.addNode(accountOne);
    data.addNode(accountTwo);
    return new Hierarchy(data);
  }

  private Hierarchy customerWithoutIdHierarchy() {
    Node data = new Node("data");
    Node customer = new Node("customer");
    customer.addNode(value("country", "GB"));
    customer.addNode(wallet("GBP", "10.50"));
    data.addNode(customer);
    return new Hierarchy(data);
  }

  private Hierarchy walletWithInputParentReferenceHierarchy() {
    Node data = new Node("data");
    Node customer = new Node("customer");
    customer.addNode(value("id", "C1"));
    Node wallet = wallet("GBP", "10.50");
    wallet.addNode(value("customer_id", "EXTERNAL-CUSTOMER"));
    customer.addNode(wallet);
    data.addNode(customer);
    return new Hierarchy(data);
  }

  private Hierarchy repeatedScalarHierarchy() {
    Node data = new Node("data");
    Node customer = new Node("customer");
    customer.addNode(value("id", "C1"));
    customer.addNode(value("tag", "vip|active"));
    customer.addNode(value("tag", "plain"));
    data.addNode(customer);
    return new Hierarchy(data);
  }

  private Hierarchy collectionItemScalarHierarchy() {
    Node data = new Node("data");
    Node first = new Node("customer");
    first.addNode(value("id", "C1"));
    first.addNode(arrayValue("tag", "active"));
    Node second = new Node("customer");
    second.addNode(value("id", "C2"));
    second.addNode(arrayValue("tag", "trial"));
    second.addNode(arrayValue("tag", "vip"));
    data.addNode(first);
    data.addNode(second);
    return new Hierarchy(data);
  }

  private Hierarchy mixedScalarThenCollectionHierarchy() {
    Node data = new Node("data");
    Node first = new Node("customer");
    first.addNode(value("id", "C1"));
    first.addNode(value("tag", "vip"));
    Node second = new Node("customer");
    second.addNode(value("id", "C2"));
    second.addNode(arrayValue("tag", "active"));
    second.addNode(arrayValue("tag", "trial"));
    data.addNode(first);
    data.addNode(second);
    return new Hierarchy(data);
  }

  private Hierarchy unusualIdentifierHierarchy() {
    Node data = new Node("data");
    Node customer = new Node("customer detail");
    customer.addNode(value("select", "select * from customer; drop table customer;"));
    data.addNode(customer);
    return new Hierarchy(data);
  }

  private Hierarchy identifierCollisionHierarchy() {
    Node data = new Node("data");
    Node lower = new Node("customer");
    lower.addNode(value("id", "C1"));
    Node upper = new Node("CUSTOMER");
    upper.addNode(value("id", "C2"));
    data.addNode(lower);
    data.addNode(upper);
    return new Hierarchy(data);
  }

  private Node customer(String id, String country, String type, Node... wallets) {
    Node customer = new Node("customer");
    customer.addNode(value("id", id));
    customer.addNode(value("country", country));
    customer.addNode(value("type", type));
    for (Node wallet : wallets) {
      customer.addNode(wallet);
    }
    return customer;
  }

  private Node customerWithCode(String id, String ccode, Node... wallets) {
    Node customer = new Node("customer");
    customer.addNode(value("id", id));
    customer.addNode(value("ccode", ccode));
    for (Node wallet : wallets) {
      customer.addNode(wallet);
    }
    return customer;
  }

  private Node country(String ccode, String name) {
    Node country = new Node("country");
    country.addNode(value("ccode", ccode));
    country.addNode(value("name", name));
    return country;
  }

  private Node wallet(String symbol, String balance) {
    Node wallet = new Node("wallet");
    wallet.addNode(value("symbol", symbol));
    wallet.addNode(value("balance", balance));
    return wallet;
  }

  private Node value(String name, String value) {
    Node node = new Node(name);
    node.setValue(value);
    return node;
  }

  private Node arrayValue(String name, String value) {
    Node node = value(name, value);
    node.setArrayItem(true);
    return node;
  }

  private int columnCount(H2Database database, String table, String column) throws Exception {
    return database.queryInt(
        "select count(*) from information_schema.columns "
            + "where table_schema = 'PUBLIC' "
            + "and table_name = '" + table.toUpperCase() + "' "
            + "and column_name = '" + column.toUpperCase() + "'");
  }

  private int tableCount(H2Database database, String table) throws Exception {
    return database.queryInt(
        "select count(*) from information_schema.tables "
            + "where table_schema = 'PUBLIC' "
            + "and table_name = '" + table.toUpperCase() + "'");
  }
}
