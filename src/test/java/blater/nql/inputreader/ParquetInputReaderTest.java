package blater.nql.inputreader;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.testsupport.ParquetTestFiles;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParquetInputReaderTest {
  @TempDir
  Path tempDir;

  @Test
  void factoryReturnsParquetInputReader() {
    assertInstanceOf(ParquetInputReader.class, InputReader.of(InputType.PARQUET));
  }

  @Test
  void mapsFlatPrimitiveRecordsAndExpandsStringTemplates() throws Exception {
    MessageType schema = ParquetTestFiles.schema("""
        message customer {
          required binary id (STRING);
          optional binary country (STRING);
          optional binary greeting (STRING);
          optional int32 age;
          optional boolean active;
        }
        """);
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Group first = factory.newGroup()
        .append("id", "C1")
        .append("country", "GB")
        .append("greeting", "Hello ${name}")
        .append("age", 42)
        .append("active", true);
    Group second = factory.newGroup()
        .append("id", "C2")
        .append("country", "US")
        .append("greeting", "Hi ${name}")
        .append("age", 7)
        .append("active", false);
    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, first, second);

    Hierarchy hierarchy = new ParquetInputReader().load(input.toString(), Map.of("name", "Fred"));

    Node root = hierarchy.getRoot();
    assertEquals("customers", root.getName());
    List<Node> customers = children(root, "customer");
    assertEquals(2, customers.size());
    assertTrue(customers.getFirst().isArrayItem());
    assertEquals("C1", child(customers.getFirst(), "id").getValue());
    assertEquals("GB", child(customers.getFirst(), "country").getValue());
    assertEquals("Hello Fred", child(customers.getFirst(), "greeting").getValue());
    assertEquals("42", child(customers.getFirst(), "age").getValue());
    assertEquals("true", child(customers.getFirst(), "active").getValue());
    assertEquals("false", child(customers.get(1), "active").getValue());
  }

  @Test
  void projectsUnsafeFileRecordAndFieldNames() throws Exception {
    MessageType schema = Types.buildMessage()
        .required(PrimitiveType.PrimitiveTypeName.BINARY)
        .as(LogicalTypeAnnotation.stringType())
        .named("event.time")
        .named("Customer Record");
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Group record = factory.newGroup().append("event.time", "2026-07-09T12:00:00Z");
    Path input = tempDir.resolve("2026-customers.parquet");
    ParquetTestFiles.write(input, schema, record);

    Hierarchy hierarchy = new ParquetInputReader().load(input.toString(), Map.of());

    assertEquals("_2026_customers", hierarchy.getRoot().getName());
    Node customer = child(hierarchy.getRoot(), "Customer_Record");
    assertEquals("2026-07-09T12:00:00Z", child(customer, "event_time").getValue());
  }

  @Test
  void flattensScalarStructsMapsAndListsIntoDomainShape() throws Exception {
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
          optional group metrics (MAP) {
            repeated group key_value {
              required binary key (STRING);
              optional group value {
                optional int32 score;
                optional binary band (STRING);
              }
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
          repeated group wallet {
            required binary symbol (STRING);
            optional binary balance (STRING);
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
    Group metrics = customer.addGroup("metrics");
    Group profileEntry = metrics.addGroup("key_value")
        .append("key", "metrics");
    profileEntry.addGroup("value")
        .append("score", 99)
        .append("band", "B");
    Group tagmap = customer.addGroup("tagmap");
    Group tags = tagmap.addGroup("key_value")
        .append("key", "flags")
        .addGroup("value");
    tags.addGroup("list").append("element", "vip");
    tags.addGroup("list").append("element", "review");
    customer.addGroup("wallet")
        .append("symbol", "GBP")
        .append("balance", "1.93");
    customer.addGroup("wallet")
        .append("symbol", "AUD")
        .append("balance", "998.33");
    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, customer);

    Node record = child(new ParquetInputReader().load(input.toString(), Map.of()).getRoot(), "customer");

    assertEquals("C1", child(record, "id").getValue());
    assertEquals("98", child(record, "profile_score").getValue());
    assertEquals("A", child(record, "profile_band").getValue());
    assertEquals("low", child(record, "risk").getValue());
    assertEquals("gold", child(record, "tier").getValue());
    assertEquals("99", child(record, "metrics_score").getValue());
    assertEquals("B", child(record, "metrics_band").getValue());
    assertEquals(List.of("vip", "review"), children(record, "flags").stream()
        .map(Node::getValue)
        .toList());
    assertTrue(child(record, "flags").isArrayItem());

    List<Node> wallets = children(record, "wallet");
    assertEquals(2, wallets.size());
    assertTrue(wallets.getFirst().isArrayItem());
    assertEquals("GBP", child(wallets.getFirst(), "symbol").getValue());
    assertEquals("AUD", child(wallets.get(1), "symbol").getValue());
  }

  @Test
  void mapKeyCollisionWithDirectFieldFailsClearly() throws Exception {
    MessageType schema = scalarMapSchema();
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Group customer = factory.newGroup().append("id", "C1");
    customer.addGroup("attributes")
        .addGroup("key_value")
        .append("key", "id")
        .append("value", "external");
    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, customer);

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> new ParquetInputReader().load(input.toString(), Map.of()));

    assertTrue(thrown.getMessage().contains("collides with direct child"));
  }

  @Test
  void projectedDuplicateMapKeysFailClearly() throws Exception {
    MessageType schema = scalarMapSchema();
    SimpleGroupFactory factory = ParquetTestFiles.factory(schema);
    Group customer = factory.newGroup().append("id", "C1");
    Group attributes = customer.addGroup("attributes");
    attributes.addGroup("key_value")
        .append("key", "user-id")
        .append("value", "7");
    attributes.addGroup("key_value")
        .append("key", "user_id")
        .append("value", "8");
    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, customer);

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> new ParquetInputReader().load(input.toString(), Map.of()));

    assertTrue(thrown.getMessage().contains("duplicate key [user_id]"));
  }

  @Test
  void projectedSiblingNameCollisionsFailClearly() throws Exception {
    MessageType schema = Types.buildMessage()
        .required(PrimitiveType.PrimitiveTypeName.BINARY)
        .as(LogicalTypeAnnotation.stringType())
        .named("user-id")
        .required(PrimitiveType.PrimitiveTypeName.BINARY)
        .as(LogicalTypeAnnotation.stringType())
        .named("user_id")
        .named("customer");
    Path input = tempDir.resolve("customers.parquet");
    ParquetTestFiles.write(input, schema, ParquetTestFiles.factory(schema).newGroup()
        .append("user-id", "7")
        .append("user_id", "8"));

    IllegalArgumentException thrown = assertThrows(
        IllegalArgumentException.class,
        () -> new ParquetInputReader().load(input.toString(), Map.of()));

    assertTrue(thrown.getMessage().contains("projected name collision"));
    assertTrue(thrown.getMessage().contains("user_id"));
  }

  private MessageType scalarMapSchema() {
    return ParquetTestFiles.schema("""
        message customer {
          required binary id (STRING);
          optional group attributes (MAP) {
            repeated group key_value {
              required binary key (STRING);
              optional binary value (STRING);
            }
          }
        }
        """);
  }

  private Node child(Node parent, String name) {
    return children(parent, name).getFirst();
  }

  private List<Node> children(Node parent, String name) {
    return parent.getChildren().stream()
        .filter(child -> child.getName().equals(name))
        .toList();
  }
}
