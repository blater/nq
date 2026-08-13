package blater.nq.runner.sql.dml.mapping;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.HierarchyPath;
import blater.nq.domain.Node;
import blater.nq.inputreader.CsvInputReader;
import blater.nq.inputreader.JsonInputReader;
import blater.nq.parser.ScriptParser;
import blater.nq.parser.script.NestStatement;
import blater.nq.runner.SyntaxErrorType;
import blater.nq.runner.sql.domain.DmlExecutionResult;
import blater.nq.runner.sql.domain.InputToColumnMap;
import blater.nq.runner.sql.domain.SqlColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StructuredInputPathResolutionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void omitsSyntheticRootForMultipleTopLevelCollections() throws Exception {
    MappingResult mapping = mapJson(
        """
            {
              "users": [
                {"id": 1, "active": true},
                {"id": 2, "active": false}
              ],
              "address": [
                {"user_id": 1, "city": "New York"}
              ]
            }
            """,
        "update users set active = {users.active} where id = {users.id};");

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(List.of(
        List.of("true", "1"),
        List.of("false", "2")), sqlValues(mapping));
  }

  @Test
  void omitsAnonymousItemForSoleRootArray() throws Exception {
    MappingResult mapping = mapJson(
        """
            {
              "users": [
                {"id": 1, "active": true},
                {"id": 2, "active": false}
              ]
            }
            """,
        "update users set active = {users.active} where id = {users.id};");

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(List.of(
        List.of("true", "1"),
        List.of("false", "2")), sqlValues(mapping));
  }

  @Test
  void retainsExplicitInternalPaths() throws Exception {
    MappingResult mapping = mapJson(
        """
            {
              "users": [
                {"id": 1, "active": true},
                {"id": 2, "active": false}
              ]
            }
            """,
        "update users set active = {users.item.active} where id = {users.item.id};");

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(2, mapping.rows().size());
  }

  @Test
  void omitsAnonymousItemForDelimitedRows() throws Exception {
    Path input = Files.writeString(
        temporaryDirectory.resolve("input.csv"),
        "person.id,person.active\n1,true\n2,false\n",
        StandardCharsets.UTF_8);
    Hierarchy hierarchy = new CsvInputReader().load(input.toString(), Map.of());
    List<InputToColumnMap> mappings = ScriptParser.parse(
            "update users set active = {csv.person.active} where id = {csv.person.id};")
        .statements().getFirst().getMappings();

    MappingResult mapping = new InputFileRowMapper().map(hierarchy, mappings, Map.of());

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(List.of(
        List.of("true", "1"),
        List.of("false", "2")), sqlValues(mapping));
  }

  @Test
  void createsWriteBackTargetsThroughTheLogicalRootArrayPath() throws Exception {
    Hierarchy hierarchy = loadJson(
        """
            {
              "users": [
                {"name": "Alice"},
                {"name": "Bob"}
              ]
            }
            """);
    NestStatement statement = ScriptParser.parse("""
        insert into users (name)
        values ({users.name})
        returns id into {users.id};
        """).statements().getFirst();
    InputFileRowMapper mapper = new InputFileRowMapper();

    MappingResult mapping = mapper.map(
        hierarchy,
        statement.getMappings(),
        statement.getReturnMappings(),
        Map.of());
    mapper.applyWriteBack(
        mapping.rows().getFirst(),
        DmlExecutionResult.of(Map.of("id", "41")));
    mapper.applyWriteBack(
        mapping.rows().get(1),
        DmlExecutionResult.of(Map.of("id", "42")));

    assertEquals(SyntaxErrorType.OK, mapping.problemStatus());
    assertEquals(List.of("41", "42"), hierarchy
        .select(HierarchyPath.fromSlashPath("/users/item/id"))
        .stream()
        .map(Node::getValue)
        .toList());
  }

  private MappingResult mapJson(String json, String script) throws Exception {
    Hierarchy hierarchy = loadJson(json);
    List<InputToColumnMap> mappings = ScriptParser.parse(script)
        .statements().getFirst().getMappings();
    return new InputFileRowMapper().map(hierarchy, mappings, Map.of());
  }

  private Hierarchy loadJson(String json) throws Exception {
    Path input = Files.writeString(
        temporaryDirectory.resolve("input.json"),
        json,
        StandardCharsets.UTF_8);
    return new JsonInputReader().load(input.toString(), Map.of());
  }

  private List<List<Object>> sqlValues(MappingResult mapping) {
    return mapping.rows().stream()
        .map(row -> row.getColumns().stream().map(SqlColumn::rawValue).toList())
        .toList();
  }
}
