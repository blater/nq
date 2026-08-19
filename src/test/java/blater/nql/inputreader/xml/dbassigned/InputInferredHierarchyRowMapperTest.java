package blater.nql.inputreader.xml.dbassigned;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.HierarchyPath;
import blater.nql.domain.Node;
import blater.nql.inputreader.XmlInputReader;
import blater.nql.runner.sql.domain.ColumnDataSourceType;
import blater.nql.runner.sql.domain.ColumnDefinition;
import blater.nql.runner.sql.domain.InputToColumnMap;
import blater.nql.runner.sql.dml.mapping.InputFileRowMapper;
import blater.nql.runner.sql.domain.DmlExecutionResult;
import blater.nql.runner.SyntaxErrorType;
import blater.nql.runner.sql.dml.mapping.MappingResult;
import blater.nql.runner.sql.domain.SqlColumn;
import blater.nql.domain.SqlType;
import blater.nql.parser.ScriptParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class InputInferredHierarchyRowMapperTest {
  @Test
  void infersRepeatedChildRowsFromNestedXml() throws Exception {
    String script = "insert into nickname (nicknameid, personid, nickname)\n"
        + "values ({message.person.nickname.@id}, {message.person.@id}, {message.person.nickname})\n"
        + ";\n";
    String inputXml = "<message>"
        + "<person id=\"7\">"
        + "<nickname id=\"10\">Ace</nickname>"
        + "<nickname id=\"11\">Al</nickname>"
        + "</person>"
        + "</message>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, sqlValues(mapping).size());
    assertEquals(Arrays.asList("10", "7", "Ace"), sqlValues(mapping).get(0));
    assertEquals(Arrays.asList("11", "7", "Al"), sqlValues(mapping).get(1));
  }

  @Test
  void infersRowContextForSiblingTerminalValues() throws Exception {
    String script = "insert into orderline (lineid, sku, quantity)\n"
        + "values ({order.line.@id}, {order.line.sku}, {order.line.quantity})\n"
        + ";\n";
    String inputXml = "<order>"
        + "<line id=\"1\"><sku>A-1</sku><quantity>2</quantity></line>"
        + "<line id=\"2\"><sku>B-2</sku><quantity>5</quantity></line>"
        + "</order>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, sqlValues(mapping).size());
    assertEquals(Arrays.asList("1", "A-1", "2"), sqlValues(mapping).get(0));
    assertEquals(Arrays.asList("2", "B-2", "5"), sqlValues(mapping).get(1));
  }

  @Test
  void infersDeepNestedRowsWithoutMixingSameNamedBranches() throws Exception {
    String script = "insert into orderline (customerid, orderid, lineid, sku, status)\n"
        + "values ({message.customer.@id}, {message.customer.order.@id}, "
        + "{message.customer.order.line.@id}, {message.customer.order.line.sku}, 'READY')\n"
        + ";\n";
    String inputXml = "<message>"
        + "<customer id=\"1\">"
        + "<order id=\"10\">"
        + "<line id=\"1\"><sku>A-1</sku></line>"
        + "<line id=\"2\"><sku>B-2</sku></line>"
        + "</order>"
        + "<order id=\"11\"><line id=\"1\"><sku>C-3</sku></line></order>"
        + "</customer>"
        + "<customer id=\"2\"><order id=\"10\"><line id=\"1\"><sku>D-4</sku></line></order></customer>"
        + "<supplier id=\"9\"><order id=\"10\"><line id=\"1\"><sku>SUPPLIER</sku></line></order></supplier>"
        + "</message>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(4, sqlValues(mapping).size());
    assertEquals(Arrays.asList("1", "10", "1", "A-1", "READY"), sqlValues(mapping).get(0));
    assertEquals(Arrays.asList("1", "10", "2", "B-2", "READY"), sqlValues(mapping).get(1));
    assertEquals(Arrays.asList("1", "11", "1", "C-3", "READY"), sqlValues(mapping).get(2));
    assertEquals(Arrays.asList("2", "10", "1", "D-4", "READY"), sqlValues(mapping).get(3));
  }

  @Test
  void infersCompositeKeysAtTheSameRepeatedEntityLevel() throws Exception {
    String script = "insert into inventory_item (warehouseid, sku, quantity)\n"
        + "values ({inventory.item.warehouseid}, {inventory.item.sku}, {inventory.item.quantity})\n"
        + ";\n";
    String inputXml = "<inventory>"
        + "<item><warehouseid>1</warehouseid><sku>A-1</sku><quantity>3</quantity></item>"
        + "<item><warehouseid>1</warehouseid><sku>B-2</sku><quantity>5</quantity></item>"
        + "</inventory>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, sqlValues(mapping).size());
    assertEquals(Arrays.asList("1", "A-1", "3"), sqlValues(mapping).get(0));
    assertEquals(Arrays.asList("1", "B-2", "5"), sqlValues(mapping).get(1));
  }

  @Test
  void infersRepeatedChildRowsWithoutKey() throws Exception {
    String script = "insert into phone (number)\n"
        + "values ({person.phone.number})\n"
        + ";\n";
    String inputXml = "<person>"
        + "<phone><number>111</number></phone>"
        + "<phone><number>222</number></phone>"
        + "</person>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, sqlValues(mapping).size());
    assertEquals(List.of("111"), sqlValues(mapping).get(0));
    assertEquals(List.of("222"), sqlValues(mapping).get(1));
  }

  @Test
  void preservesMissingRepeatedRowSourceAsOneMissingRow() throws Exception {
    String script = "insert into orderline (lineid, sku)\n"
        + "values ({order.line.@id}, {order.line.sku})\n"
        + ";\n";
    String inputXml = "<order id=\"99\"/>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(1, sqlValues(mapping).size());
    assertEquals(Arrays.asList(null, null), sqlValues(mapping).getFirst());
  }

  @Test
  void reportsAmbiguousSiblingRepeatedLists() throws Exception {
    String script = "insert into person_phone (personid, phone_number, phone_label)\n"
        + "values ({person.@id}, {person.phone}, {person.phoneLabel})\n"
        + ";\n";
    String inputXml = "<person id=\"7\">"
        + "<phone>111</phone>"
        + "<phone>222</phone>"
        + "<phoneLabel>home</phoneLabel>"
        + "<phoneLabel>work</phoneLabel>"
        + "</person>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT, mapping.problemStatus());
    assertEquals(0, mapping.rows().size());
  }

  @Test
  void reportsAmbiguousIndependentBranches() throws Exception {
    String script = "insert into contact_pair (customer_phone, supplier_phone)\n"
        + "values ({message.customer.phone}, {message.supplier.phone})\n"
        + ";\n";
    String inputXml = "<message>"
        + "<customer><phone>111</phone><phone>222</phone></customer>"
        + "<supplier><phone>999</phone><phone>888</phone></supplier>"
        + "</message>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.AMBIGUOUS_ROW_CONTEXT, mapping.problemStatus());
    assertEquals(0, mapping.rows().size());
  }

  @Test
  void reportsDuplicateTargetColumnAssignment() throws Exception {
    String script = "insert into phone (number, number)\n"
        + "values ({person.phone.number}, {person.phone.label})\n"
        + ";\n";
    String inputXml = "<person>"
        + "<phone><number>111</number><label>home</label></phone>"
        + "</person>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.DUPLICATE_TARGET_COLUMN_ASSIGNMENT, mapping.problemStatus());
    assertEquals(0, mapping.rows().size());
  }

  @Test
  void reportsUnsupportedSourcePath() throws Exception {
    Hierarchy hierarchy = hierarchy("<person><firstname>Fred</firstname></person>");
    InputToColumnMap mapping = new InputToColumnMap(
        new ColumnDefinition("firstname", SqlType.STRING, "", false, -99, ColumnDataSourceType.NORMAL),
        "/person/firstname[1]",
        null,
        false);

    MappingResult result = new InputFileRowMapper().map(hierarchy, List.of(mapping), Map.of());

    assertEquals(SyntaxErrorType.UNSUPPORTED_SOURCE_PATH, result.problemStatus());
    assertEquals(0, result.rows().size());
  }

  @Test
  void normalizesTerminalElementResultsToTheirParentRowContext() throws Exception {
    String script = "insert into phone (number)\n"
        + "values ({person.phone})\n"
        + ";\n";
    String inputXml = "<person>"
        + "<phone>111</phone>"
        + "<phone>222</phone>"
        + "</person>";

    MappingResult mapping = infer(script, inputXml);

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, sqlValues(mapping).size());
    assertEquals(List.of("111"), sqlValues(mapping).get(0));
    assertEquals(List.of("222"), sqlValues(mapping).get(1));
  }

  @Test
  void ordinaryColumnsDoNotRegisterWriteBackNodes() throws Exception {
    String script = "insert into person (firstname)\n"
        + "values ({person.firstname})\n"
        + ";\n";
    Hierarchy hierarchy = hierarchy("<person><firstname>Fred</firstname></person>");
    InputFileRowMapper mapper = new InputFileRowMapper();

    MappingResult mapping = mapper.map(hierarchy, parseMappings(script), Map.of());

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(0, mapper.registeredWriteBackNodes(mapping.rows().getFirst()).size());
  }

  @Test
  void missingReturnTargetIsCreatedAndRegisteredByDirectReference() throws Exception {
    String script = "insert into person (firstname)\n"
        + "values ({message.person.firstname})\n"
        + "returns personid into {message.person.id}\n"
        + ";\n";
    Hierarchy hierarchy = hierarchy("<message><person><firstname>Fred</firstname></person></message>");
    InputFileRowMapper mapper = new InputFileRowMapper();

    var statement = ScriptParser.parse(script).statements().getFirst();
    MappingResult mapping = mapper.map(
        hierarchy,
        statement.getMappings(),
        statement.getReturnMappings(),
        Map.of());
    Node idElement = hierarchy.select(HierarchyPath.fromSlashPath("/message/person/id")).getFirst();

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals("", idElement.getValue());
    assertEquals(1, mapper.registeredWriteBackNodes(mapping.rows().getFirst()).size());
    assertSame(idElement, mapper.registeredWriteBackNodes(mapping.rows().getFirst()).getFirst().node());

    mapper.applyWriteBack(
        mapping.rows().getFirst(),
        DmlExecutionResult.of(Map.of("personid", "42")));

    assertEquals("42", idElement.getValue());
  }

  private MappingResult infer(String script, String xml) throws Exception {
    Hierarchy hierarchy = hierarchy(xml);
    List<InputToColumnMap> mappings = parseMappings(script);
    return new InputFileRowMapper().map(hierarchy, mappings, Map.of());
  }

  private List<List<Object>> sqlValues(MappingResult mapping) {
    return mapping.rows().stream()
        .map(row -> row.getColumns().stream().map(SqlColumn::rawValue).toList())
        .toList();
  }

  private List<InputToColumnMap> parseMappings(String script) throws Exception {
    return ScriptParser.parse(script).statements().getFirst().getMappings();
  }

  private Hierarchy hierarchy(String xml) throws Exception {
    Path tempFile = Files.createTempFile("hiql-mapper-", ".xml");
    try {
      Files.writeString(tempFile, xml, StandardCharsets.UTF_8);
      return new XmlInputReader().load(tempFile.toString(), Map.of());
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }
}
