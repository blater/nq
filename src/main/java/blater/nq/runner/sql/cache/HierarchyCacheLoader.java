package blater.nq.runner.sql.cache;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
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
  private static final String SOURCE_ID_COLUMN = "id";
  private static final String GENERATED_ID_COLUMN = "_nq_id";
  private static final String RESERVED_PREFIX = "_nq_";
  private static final int INSERT_BATCH_SIZE = 500;

  private final SqlExecutor sqlExecutor;
  private final Map<String, TableState> tables = new LinkedHashMap<>();
  private final Map<String, Boolean> sourceIdentityByTable = new LinkedHashMap<>();

  public HierarchyCacheLoader(SqlExecutor sqlExecutor) {
    this.sqlExecutor = sqlExecutor;
  }

  public void load(Hierarchy hierarchy) {
    tables.clear();
    sourceIdentityByTable.clear();
    if (hierarchy == null || hierarchy.getRoot() == null) {
      return;
    }

    Node root = hierarchy.getRoot();
    validateInputNames(root);
    analyzeNode(root, null, true, root.getName());
    writeNode(root, null, null, true, root.getName());
    flushAllTables();
    for (TableState table : tables.values()) {
      if (table.created) {
        Log.debug("Cache table [{}]", table.logicalName);
      }
    }
  }

  /*
   * Responsibility: Chooses one stable identity strategy for every inferred
   * relation before rows and containment references are emitted. A source id
   * is the relation identity only when every materialized row supplies one;
   * otherwise the relation receives a generated _nq_id column.
   */
  private void analyzeNode(
      Node node,
      String parentTable,
      boolean root,
      String rootName) {
    Map<String, List<String>> valuesByName = new LinkedHashMap<>();
    Set<String> repeatedNames = new LinkedHashSet<>();
    List<Node> objectChildren = new ArrayList<>();
    classifyDirectChildren(node, valuesByName, repeatedNames, objectChildren);

    String currentTable = parentTable;
    if (shouldMaterialize(node, valuesByName, objectChildren, root)) {
      currentTable = relationName(node, parentTable, rootName);
      boolean hasSourceId = firstScalarValue(valuesByName, SOURCE_ID_COLUMN, null) != null;
      sourceIdentityByTable.merge(currentTable, hasSourceId, (left, right) -> left && right);
    }

    for (Node child : objectChildren) {
      analyzeNode(child, currentTable, false, rootName);
    }
  }

  private void validateInputNames(Node node) {
    if (node.getName() != null
        && node.getName().toLowerCase(java.util.Locale.ROOT).startsWith(RESERVED_PREFIX)) {
      Log.fatal(
          IllegalArgumentException.class,
          "Input name [" + node.getName() + "] uses reserved prefix " + RESERVED_PREFIX);
    }
    for (Node child : node.getChildren()) {
      validateInputNames(child);
    }
  }

  private void writeNode(
      Node node,
      String parentTable,
      String parentId,
      boolean root,
      String rootName) {
    Map<String, List<String>> valuesByName = new LinkedHashMap<>();
    Set<String> repeatedNames = new LinkedHashSet<>();
    List<Node> objectChildren = new ArrayList<>();
    classifyDirectChildren(node, valuesByName, repeatedNames, objectChildren);

    String currentTable = parentTable;
    String currentId = parentId;
    if (shouldMaterialize(node, valuesByName, objectChildren, root)) {
      String logicalName = relationName(node, parentTable, rootName);
      TableState table = table(logicalName, false);
      currentId = table.writeObjectRow(parentTable, parentId, valuesByName, repeatedNames);
      currentTable = logicalName;
    }

    for (Node child : objectChildren) {
      writeNode(child, currentTable, currentId, false, rootName);
    }
  }

  private String relationName(Node node, String parentTable, String rootName) {
    if (parentTable == null
        && node.isArrayItem()
        && "item".equals(node.getName())
        && rootName != null
        && !Set.of("json", "yaml", "csv", "tsv", "toml").contains(rootName)) {
      return rootName;
    }
    return node.getName();
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

  private TableState table(String logicalName, boolean valueTable) {
    TableState existing = tables.get(logicalName);
    if (existing != null) {
      if (existing.valueTable != valueTable) {
        Log.fatal(
            IllegalArgumentException.class,
            "Cache table name collision for [" + logicalName + "]");
      }
      return existing;
    }

    boolean sourceIdentity = !valueTable
        && Boolean.TRUE.equals(sourceIdentityByTable.get(logicalName));
    TableState table = new TableState(logicalName, valueTable, sourceIdentity);
    tables.put(logicalName, table);
    return table;
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
    private final String logicalName;
    private final boolean valueTable;
    private final boolean sourceIdentity;
    private final Map<String, String> columnsByLogicalName = new LinkedHashMap<>();
    private final Map<String, FieldStorageType> fieldStorageTypesByLogicalName = new LinkedHashMap<>();
    private final List<Map<String, Object>> pendingRows = new ArrayList<>();
    private int nextGeneratedId = 1;
    private boolean created = false;

    private TableState(String logicalName, boolean valueTable, boolean sourceIdentity) {
      this.logicalName = logicalName;
      this.valueTable = valueTable;
      this.sourceIdentity = sourceIdentity;
    }

    private String writeObjectRow(
        String parentTable,
        String parentId,
        Map<String, List<String>> valuesByName,
        Set<String> repeatedNames) {

      String rowId = sourceIdentity
          ? firstScalarValue(valuesByName, SOURCE_ID_COLUMN, null)
          : nextId();

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
            + "from " + logicalName + " "
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
        sqlExecutor.execute("alter table " + logicalName + " drop column " + renderedFieldColumn);
      }
      fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.VALUE_TABLE);
    }

    private TableState ensureValueTable(String fieldName) {
      String valueTableName = repeatedTableName(logicalName, fieldName);
      TableState valueTableState = table(
          valueTableName,
          true);
      valueTableState.ensureColumn(valueTableState.rowIdColumn());
      valueTableState.ensureColumn(parentIdColumn(logicalName));
      valueTableState.ensureColumn("value");
      return valueTableState;
    }

    private void ensureColumn(String logicalColumnName) {
      if (columnsByLogicalName.containsKey(logicalColumnName)) {
        return;
      }

      if (!created) {
        columnsByLogicalName.put(logicalColumnName, logicalColumnName);
        sqlExecutor.execute(
            "create table " + logicalName
                + " (" + logicalColumnName + " " + TEXT_SQL_TYPE + ")");
        created = true;
        return;
      }

      flush();
      columnsByLogicalName.put(logicalColumnName, logicalColumnName);
      sqlExecutor.execute(
          "alter table " + logicalName
              + " add column " + logicalColumnName + " " + TEXT_SQL_TYPE);
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
      String insert = "insert into " + logicalName
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

    private String nextId() {
      return Integer.toString(nextGeneratedId++);
    }

    private String rowIdColumn() {
      return sourceIdentity ? SOURCE_ID_COLUMN : GENERATED_ID_COLUMN;
    }
  }

  private static String firstScalarValue(
      Map<String, List<String>> valuesByName,
      String fieldName,
      String defaultValue) {
    List<String> values = valuesByName.get(fieldName);
    if (values == null || values.isEmpty()) {
      return defaultValue;
    }
    return values.getFirst();
  }
}
