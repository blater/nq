package blater.nq.runner.sql.cache;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.inputreader.InputDocument;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.runner.sql.SqlRowCursor;
import blater.nq.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * Responsibility: Materializes a neutral hierarchy into queryable
 * SQL cache tables for --cache mode.
 */
public final class HierarchyCacheLoader {
  private static final String TEXT_SQL_TYPE = "varchar";
  private static final int INSERT_BATCH_SIZE = 500;

  private final SqlExecutor sqlExecutor;
  private final Map<RelationMaterializationPlan.StorageKey, TableState> tables = new LinkedHashMap<>();
  private final Map<String, String> tableSqlIdentities = new LinkedHashMap<>();

  public HierarchyCacheLoader(SqlExecutor sqlExecutor) {
    this.sqlExecutor = sqlExecutor;
  }

  public void load(Hierarchy hierarchy) {
    load(InputDocument.fromHierarchy(hierarchy), MaterializationConfiguration.from(Map.of()));
  }

  public void load(InputDocument document, MaterializationConfiguration configuration) {
    tables.clear();
    tableSqlIdentities.clear();
    Hierarchy hierarchy = document == null ? null : document.hierarchy();
    if (hierarchy == null || hierarchy.getRoot() == null) {
      return;
    }

    RelationMaterializationPlan plan = RelationMaterializationPlan.create(document, configuration);
    writeNode(hierarchy.getRoot(), null, null, true, plan);
    flushAllTables();
    for (TableState table : tables.values()) {
      if (table.created) {
        Log.debug("Cache table [{}]", table.logicalName);
      }
    }
  }

  private void writeNode(
      Node node,
      String parentTable,
      String parentId,
      boolean root,
      RelationMaterializationPlan plan) {
    Map<String, List<String>> valuesByName = new LinkedHashMap<>();
    Set<String> repeatedNames = new LinkedHashSet<>();
    List<Node> objectChildren = new ArrayList<>();
    classifyDirectChildren(node, valuesByName, repeatedNames, objectChildren);

    String currentTable = parentTable;
    String currentId = parentId;
    if (shouldMaterialize(node, valuesByName, objectChildren, root)) {
      RelationMaterializationPlan.PlannedRelation relation = plan.relation(node);
      TableState table = table(relation.storageKey(), relation.logicalName(), false);
      currentId = table.writeObjectRow(parentTable, parentId, valuesByName, repeatedNames);
      currentTable = relation.logicalName();
    }

    for (Node child : objectChildren) {
      writeNode(child, currentTable, currentId, false, plan);
    }
  }

  private void classifyDirectChildren(
      Node node,
      Map<String, List<String>> valuesByName,
      Set<String> repeatedNames,
      List<Node> objectChildren) {

    for (Node child : node.getChildren()) {
      if (isScalar(child)) {
        valuesByName.computeIfAbsent(child.getName(), ignored -> new ArrayList<>())
            .add(nodeValue(child));
        if (child.isArrayItem()) {
          repeatedNames.add(child.getName());
        }
      } else if (!child.isAttribute()) {
        objectChildren.add(child);
      }
    }

    for (Map.Entry<String, List<String>> value : valuesByName.entrySet()) {
      if (value.getValue().size() > 1) {
        repeatedNames.add(value.getKey());
      }
    }
  }

  private boolean shouldMaterialize(
      Node node,
      Map<String, List<String>> valuesByName,
      List<Node> objectChildren,
      boolean root) {

    if (node.isAttribute()) {
      return false;
    }
    boolean emptyObject = !root && !node.hasValue() && node.getChildren().isEmpty();
    return !valuesByName.isEmpty() || (!root && (!objectChildren.isEmpty() || emptyObject));
  }

  private boolean isScalar(Node node) {
    if (node.isAttribute()) {
      return true;
    }
    boolean hasElementChildren = node.getChildren().stream().anyMatch(child -> !child.isAttribute());
    return node.hasValue() && !hasElementChildren;
  }

  private String nodeValue(Node node) {
    if (node.isNull()) {
      return null;
    }
    return node.getValue() == null ? "" : node.getValue();
  }

  private void flushAllTables() {
    for (TableState table : new ArrayList<>(tables.values())) {
      table.flush();
    }
  }

  private TableState table(
      RelationMaterializationPlan.StorageKey storageKey,
      String logicalName,
      boolean valueTable) {
    TableState existing = tables.get(storageKey);
    if (existing != null) {
      if (existing.valueTable != valueTable) {
        Log.fatal(
            IllegalArgumentException.class,
            "Cache table name collision for [" + logicalName + "]");
      }
      return existing;
    }

    String renderedName = CacheIdentifierNaming.render(logicalName, "table");
    String sqlIdentity = CacheIdentifierNaming.sqlIdentity(logicalName);
    String existingLogicalName = tableSqlIdentities.putIfAbsent(sqlIdentity, logicalName);
    if (existingLogicalName != null && !existingLogicalName.equals(logicalName)) {
      Log.fatal(
          IllegalArgumentException.class,
          "Cache table SQL identifier collision between ["
              + existingLogicalName + "] and [" + logicalName + "]");
    }

    TableState table = new TableState(storageKey, logicalName, renderedName, valueTable);
    tables.put(storageKey, table);
    return table;
  }

  private String rowIdColumn() {
    return "id";
  }

  private String parentIdColumn(String parentTable) {
    return parentTable + "_id";
  }

  private String repeatedTableName(String parentTable, String fieldName) {
    return parentTable + "_" + fieldName;
  }

  private enum FieldStorageType {
    COLUMN,
    VALUE_TABLE
  }

  private final class TableState {
    private final RelationMaterializationPlan.StorageKey storageKey;
    private final String logicalName;
    private final String renderedSqlName;
    private final boolean valueTable;
    private final Map<String, String> columnsByLogicalName = new LinkedHashMap<>();
    private final Map<String, String> columnSqlIdentities = new LinkedHashMap<>();
    private final Map<String, FieldStorageType> fieldStorageTypesByLogicalName = new LinkedHashMap<>();
    private final List<Map<String, Object>> pendingRows = new ArrayList<>();
    private int nextGeneratedId = 1;
    private boolean created = false;

    private TableState(
        RelationMaterializationPlan.StorageKey storageKey,
        String logicalName,
        String renderedSqlName,
        boolean valueTable) {
      this.storageKey = storageKey;
      this.logicalName = logicalName;
      this.renderedSqlName = renderedSqlName;
      this.valueTable = valueTable;
    }

    private String writeObjectRow(
        String parentTable,
        String parentId,
        Map<String, List<String>> valuesByName,
        Set<String> repeatedNames) {

      String rowId = firstScalarValue(valuesByName, rowIdColumn(), null);
      if (rowId == null) {
        rowId = nextId();
      }

      ensureColumn(rowIdColumn());
      String parentColumn = parentTable == null ? null : parentIdColumn(parentTable);
      if (parentColumn != null) {
        ensureColumn(parentColumn);
      }

      for (Map.Entry<String, List<String>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (isStructuralColumn(fieldName, parentColumn)) {
          continue;
        }
        prepareFieldStorage(fieldName, repeatedNames.contains(fieldName));
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(rowIdColumn(), rowId);
      if (parentColumn != null) {
        row.put(parentColumn, firstScalarValue(valuesByName, parentColumn, parentId));
      }

      for (Map.Entry<String, List<String>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (isStructuralColumn(fieldName, parentColumn)) {
          continue;
        }
        if (fieldStorageTypesByLogicalName.get(fieldName) == FieldStorageType.COLUMN) {
          row.put(fieldName, firstScalarValue(valuesByName, fieldName, null));
        }
      }

      pendingRows.add(row);
      flushIfFull();

      for (Map.Entry<String, List<String>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (!isStructuralColumn(fieldName, parentColumn)
            && fieldStorageTypesByLogicalName.get(fieldName) == FieldStorageType.VALUE_TABLE) {
          writeRepeatedValueRows(rowId, fieldName, field.getValue());
        }
      }

      return rowId;
    }

    private void writeRepeatedValueRows(String parentId, String fieldName, List<String> values) {
      for (String value : values) {
        writeRepeatedValueRow(parentId, fieldName, value);
      }
    }

    private void writeRepeatedValueRow(String parentId, String fieldName, String value) {
      TableState valueTableState = ensureValueTable(fieldName);
      valueTableState.writeValueTableRow(logicalName, parentId, value);
    }

    private void writeValueTableRow(String parentTable, String parentId, String value) {
      ensureColumn(rowIdColumn());
      ensureColumn(parentIdColumn(parentTable));
      ensureColumn("value");

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(rowIdColumn(), nextId());
      row.put(parentIdColumn(parentTable), parentId);
      row.put("value", value);
      pendingRows.add(row);
      flushIfFull();
    }

    private void prepareFieldStorage(String fieldName, boolean repeated) {
      FieldStorageType storageType = fieldStorageTypesByLogicalName.get(fieldName);
      if (storageType == FieldStorageType.VALUE_TABLE) {
        ensureValueTable(fieldName);
        return;
      }
      if (storageType == FieldStorageType.COLUMN && repeated) {
        promoteField(fieldName);
        return;
      }
      if (storageType == null && repeated) {
        fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.VALUE_TABLE);
        ensureValueTable(fieldName);
        return;
      }
      if (storageType == null) {
        fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.COLUMN);
      }
      ensureColumn(fieldName);
    }

    private void promoteField(String fieldName) {
      flush();
      ensureValueTable(fieldName);

      String renderedFieldColumn = columnsByLogicalName.get(fieldName);
      if (renderedFieldColumn != null) {
        String select = "select "
            + columnsByLogicalName.get(rowIdColumn()) + " as \"cache_parent_id\", "
            + renderedFieldColumn + " as \"cache_field_value\" "
            + "from " + renderedSqlName + " "
            + "where " + renderedFieldColumn + " is not null";

        try (SqlRowCursor rows = sqlExecutor.query(select)) {
          while (rows.next()) {
            writeRepeatedValueRow(
                rows.row().getStringValue("cache_parent_id"),
                fieldName,
                rows.row().getStringValue("cache_field_value"));
          }
        }

        columnsByLogicalName.remove(fieldName);
        sqlExecutor.execute("alter table " + renderedSqlName + " drop column " + renderedFieldColumn);
      }
      fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.VALUE_TABLE);
    }

    private TableState ensureValueTable(String fieldName) {
      String valueTableName = repeatedTableName(logicalName, fieldName);
      TableState valueTableState = table(
          new RelationMaterializationPlan.StorageKey(storageKey.value() + "#value:" + fieldName),
          valueTableName,
          true);
      valueTableState.ensureColumn(rowIdColumn());
      valueTableState.ensureColumn(parentIdColumn(logicalName));
      valueTableState.ensureColumn("value");
      return valueTableState;
    }

    private void ensureColumn(String logicalColumnName) {
      if (columnsByLogicalName.containsKey(logicalColumnName)) {
        return;
      }

      String renderedColumnName = CacheIdentifierNaming.render(logicalColumnName, "column");
      String sqlIdentity = CacheIdentifierNaming.sqlIdentity(logicalColumnName);
      String existingLogicalName = columnSqlIdentities.putIfAbsent(sqlIdentity, logicalColumnName);
      if (existingLogicalName != null && !existingLogicalName.equals(logicalColumnName)) {
        Log.fatal(
            IllegalArgumentException.class,
            "Cache column SQL identifier collision in table ["
                + logicalName + "] between ["
                + existingLogicalName + "] and [" + logicalColumnName + "]");
      }

      if (!created) {
        columnsByLogicalName.put(logicalColumnName, renderedColumnName);
        sqlExecutor.execute(
            "create table " + renderedSqlName
                + " (" + renderedColumnName + " " + TEXT_SQL_TYPE + ")");
        created = true;
        return;
      }

      flush();
      columnsByLogicalName.put(logicalColumnName, renderedColumnName);
      sqlExecutor.execute(
          "alter table " + renderedSqlName
              + " add column " + renderedColumnName + " " + TEXT_SQL_TYPE);
    }

    private void flushIfFull() {
      if (pendingRows.size() >= INSERT_BATCH_SIZE) {
        flush();
      }
    }

    private void flush() {
      if (pendingRows.isEmpty()) {
        return;
      }

      String columns = String.join(", ", columnsByLogicalName.values());
      String placeholders = columnsByLogicalName.keySet().stream()
          .map(ignored -> "?")
          .collect(Collectors.joining(", "));
      String insert = "insert into " + renderedSqlName
          + " (" + columns + ") values (" + placeholders + ")";

      List<List<Object>> rows = new ArrayList<>(pendingRows.size());
      for (Map<String, Object> pendingRow : pendingRows) {
        List<Object> values = new ArrayList<>(columnsByLogicalName.size());
        for (String column : columnsByLogicalName.keySet()) {
          values.add(pendingRow.get(column));
        }
        rows.add(values);
      }
      sqlExecutor.executeBatch(insert, rows);
      pendingRows.clear();
    }

    private boolean isStructuralColumn(String fieldName, String parentColumn) {
      return rowIdColumn().equals(fieldName) || (parentColumn != null && parentColumn.equals(fieldName));
    }

    private String firstScalarValue(
        Map<String, List<String>> valuesByName,
        String fieldName,
        String defaultValue) {

      List<String> values = valuesByName.get(fieldName);
      if (values == null || values.isEmpty()) {
        return defaultValue;
      }
      return values.getFirst();
    }

    private String nextId() {
      return Integer.toString(nextGeneratedId++);
    }
  }
}
