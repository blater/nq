package blater.nql.runner.inference;

import blater.nql.runner.sql.Catalog;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blater.nql.runner.inference.DatabaseStructure.KeyEvidence.PRIMARY_KEY;
import static blater.nql.runner.inference.DatabaseStructure.KeyEvidence.UNIQUE_INDEX;
import static blater.nql.runner.inference.DatabaseStructure.RelationshipEvidence.DECLARED_FOREIGN_KEY;

/** Reads one complete visible database graph from JDBC metadata. */
public final class DatabaseStructureInferrer {
  private static final String[] RELATION_TYPES = {"TABLE", "BASE TABLE", "VIEW"};

  private DatabaseStructureInferrer() {
  }

  public static DatabaseStructure infer(Connection connection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    List<DatabaseStructure.Relation> relations = new ArrayList<>();
    try (ResultSet tables = metadata.getTables(null, null, "%", RELATION_TYPES)) {
      while (tables.next()) {
        String catalog = tables.getString("TABLE_CAT");
        String schema = tables.getString("TABLE_SCHEM");
        String table = tables.getString("TABLE_NAME");
        if (!Catalog.isUserSchema(catalog, schema) || "NQL_INTERNAL".equalsIgnoreCase(schema)) continue;
        DatabaseStructure.RelationId id = new DatabaseStructure.RelationId(catalog, schema, table);
        List<DatabaseStructure.Column> columns = columns(metadata, id);
        List<DatabaseStructure.CandidateKey> keys = new ArrayList<>();
        keys.addAll(primaryKeys(metadata, id));
        keys.addAll(uniqueIndexes(metadata, id));
        keys.addAll(SchemaNamingHeuristics.conventionalKeys(id, columns));
        keys = deduplicateKeys(keys);
        relations.add(new DatabaseStructure.Relation(id, tables.getString("TABLE_TYPE"), columns, keys));
      }
    }

    List<DatabaseStructure.Relationship> relationships = declaredRelationships(metadata, relations);
    relationships.addAll(SchemaNamingHeuristics.inferredRelationships(relations, relationships));

    List<DatabaseStructure.Relation> completed = new ArrayList<>();
    for (DatabaseStructure.Relation relation : relations) {
      List<DatabaseStructure.CandidateKey> keys = new ArrayList<>(relation.candidateKeys());
      keys.addAll(SchemaNamingHeuristics.logicalLinkKeys(relation, relationships));
      completed.add(new DatabaseStructure.Relation(
          relation.id(), relation.type(), relation.columns(), deduplicateKeys(keys)));
    }
    completed.sort(Comparator.comparing(item -> item.id().qualifiedName(), String.CASE_INSENSITIVE_ORDER));
    return new DatabaseStructure(completed, relationships, System.currentTimeMillis());
  }

  private static List<DatabaseStructure.Column> columns(
      DatabaseMetaData metadata,
      DatabaseStructure.RelationId relation) throws SQLException {
    List<DatabaseStructure.Column> result = new ArrayList<>();
    try (ResultSet columns = metadata.getColumns(
        relation.catalog(), relation.schema(), relation.name(), "%")) {
      while (columns.next()) {
        result.add(new DatabaseStructure.Column(
            columns.getString("COLUMN_NAME"),
            columns.getInt("DATA_TYPE"),
            columns.getString("TYPE_NAME"),
            columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
            columns.getInt("ORDINAL_POSITION")));
      }
    }
    result.sort(Comparator.comparingInt(DatabaseStructure.Column::ordinal));
    return result;
  }

  private static List<DatabaseStructure.CandidateKey> primaryKeys(
      DatabaseMetaData metadata,
      DatabaseStructure.RelationId relation) throws SQLException {
    Map<String, Map<Integer, String>> keys = new LinkedHashMap<>();
    try (ResultSet rows = metadata.getPrimaryKeys(relation.catalog(), relation.schema(), relation.name())) {
      while (rows.next()) {
        String name = rows.getString("PK_NAME");
        if (name == null) name = "PRIMARY";
        keys.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
            .put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
      }
    }
    return keys.entrySet().stream()
        .map(entry -> new DatabaseStructure.CandidateKey(
            entry.getKey(), ordered(entry.getValue()), PRIMARY_KEY))
        .toList();
  }

  private static List<DatabaseStructure.CandidateKey> uniqueIndexes(
      DatabaseMetaData metadata,
      DatabaseStructure.RelationId relation) throws SQLException {
    Map<String, Map<Integer, String>> indexes = new LinkedHashMap<>();
    java.util.Set<String> unusable = new java.util.HashSet<>();
    try (ResultSet rows = metadata.getIndexInfo(
        relation.catalog(), relation.schema(), relation.name(), true, false)) {
      while (rows.next()) {
        String name = rows.getString("INDEX_NAME");
        String column = rows.getString("COLUMN_NAME");
        if (name == null || rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
        if (column == null) {
          unusable.add(name);
          indexes.remove(name);
          continue;
        }
        if (unusable.contains(name)) continue;
        indexes.computeIfAbsent(name, ignored -> new LinkedHashMap<>())
            .put(rows.getInt("ORDINAL_POSITION"), column);
      }
    }
    return indexes.entrySet().stream()
        .filter(entry -> !unusable.contains(entry.getKey()))
        .map(entry -> new DatabaseStructure.CandidateKey(
            entry.getKey(), ordered(entry.getValue()), UNIQUE_INDEX))
        .toList();
  }

  private static List<DatabaseStructure.Relationship> declaredRelationships(
      DatabaseMetaData metadata,
      List<DatabaseStructure.Relation> relations) throws SQLException {
    List<DatabaseStructure.Relationship> result = new ArrayList<>();
    for (DatabaseStructure.Relation relation : relations) {
      record ForeignKeyParts(
          DatabaseStructure.RelationId target,
          Map<Integer, String> sourceColumns,
          Map<Integer, String> targetColumns) {
      }
      Map<String, ForeignKeyParts> keys = new LinkedHashMap<>();
      try (ResultSet rows = metadata.getImportedKeys(
          relation.id().catalog(), relation.id().schema(), relation.name())) {
        while (rows.next()) {
          String name = rows.getString("FK_NAME");
          if (name == null) name = "FK_" + rows.getString("PKTABLE_NAME") + "_" + rows.getString("FKCOLUMN_NAME");
          DatabaseStructure.RelationId target = new DatabaseStructure.RelationId(
              rows.getString("PKTABLE_CAT"), rows.getString("PKTABLE_SCHEM"), rows.getString("PKTABLE_NAME"));
          ForeignKeyParts parts = keys.computeIfAbsent(name,
              ignored -> new ForeignKeyParts(target, new LinkedHashMap<>(), new LinkedHashMap<>()));
          int sequence = rows.getInt("KEY_SEQ");
          parts.sourceColumns().put(sequence, rows.getString("FKCOLUMN_NAME"));
          parts.targetColumns().put(sequence, rows.getString("PKCOLUMN_NAME"));
        }
      }
      keys.forEach((name, parts) -> result.add(new DatabaseStructure.Relationship(
          name,
          relation.id(),
          ordered(parts.sourceColumns()),
          parts.target(),
          ordered(parts.targetColumns()),
          DECLARED_FOREIGN_KEY)));
    }
    return result;
  }

  private static List<String> ordered(Map<Integer, String> columns) {
    return columns.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
  }

  private static List<DatabaseStructure.CandidateKey> deduplicateKeys(
      List<DatabaseStructure.CandidateKey> keys) {
    Map<String, DatabaseStructure.CandidateKey> unique = new LinkedHashMap<>();
    for (DatabaseStructure.CandidateKey key : keys.stream().sorted(DatabaseStructure.CandidateKey.ORDER).toList()) {
      String signature = key.columns().stream().map(String::toLowerCase).reduce((a, b) -> a + "\0" + b).orElse("");
      unique.putIfAbsent(signature, key);
    }
    return List.copyOf(unique.values());
  }
}
