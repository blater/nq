package blater.nql.runner.sql.cache;

import blater.nql.domain.Hierarchy;
import blater.nql.domain.Node;
import blater.nql.domain.ScalarKind;
import blater.nql.runner.sql.SqlExecutor;
import blater.nql.runner.sql.SqlRowCursor;
import blater.nql.util.Log;

import java.math.BigDecimal;
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
  private static final String SOURCE_ID_COLUMN = "id";
  private static final String GENERATED_ID_COLUMN = "_nql_id";
  private static final String RESERVED_PREFIX = "_nql_";
  private static final int INSERT_BATCH_SIZE = 500;

  private final SqlExecutor sqlExecutor;
  private final Map<String, TableState> tables = new LinkedHashMap<>();
  private final Map<String, Boolean> sourceIdentityByTable = new LinkedHashMap<>();
  private final Map<String, Map<String, ScalarKind>> scalarKindsByTable = new LinkedHashMap<>();

  public HierarchyCacheLoader(SqlExecutor sqlExecutor) {
    this.sqlExecutor = sqlExecutor;
  }

  public void load(Hierarchy hierarchy) {
    tables.clear();
    sourceIdentityByTable.clear();
    scalarKindsByTable.clear();
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
   * otherwise the relation receives a generated _nql_id column.
   */
  private void analyzeNode(
      Node node,
      String parentTable,
      boolean root,
      String rootName) {
    Map<String, List<ScalarValue>> valuesByName = new LinkedHashMap<>();
    Set<String> repeatedNames = new LinkedHashSet<>();
    List<Node> objectChildren = new ArrayList<>();
    classifyDirectChildren(node, valuesByName, repeatedNames, objectChildren);

    String currentTable = parentTable;
    if (shouldMaterialize(node, valuesByName, objectChildren, root)) {
      currentTable = relationName(node, parentTable, rootName);
      ScalarValue sourceId = firstScalarValue(valuesByName, SOURCE_ID_COLUMN);
      boolean hasSourceId = sourceId != null && sourceId.value() != null;
      sourceIdentityByTable.merge(currentTable, hasSourceId, (left, right) -> left && right);
      mergeScalarKinds(currentTable, valuesByName);
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
      Object parentId,
      boolean root,
      String rootName) {
    Map<String, List<ScalarValue>> valuesByName = new LinkedHashMap<>();
    Set<String> repeatedNames = new LinkedHashSet<>();
    List<Node> objectChildren = new ArrayList<>();
    classifyDirectChildren(node, valuesByName, repeatedNames, objectChildren);

    String currentTable = parentTable;
    Object currentId = parentId;
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
      Map<String, List<ScalarValue>> valuesByName,
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

    for (Map.Entry<String, List<ScalarValue>> value : valuesByName.entrySet()) {
      if (value.getValue().size() > 1) {
        repeatedNames.add(value.getKey());
      }
    }
  }

  private boolean shouldMaterialize(
      Node node,
      Map<String, List<ScalarValue>> valuesByName,
      List<Node> objectChildren,
      boolean root) {

    if (node.isAttribute()) {
      return false;
    }
    if (node.isCollection() && node.getChildren().isEmpty()) {
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

  private ScalarValue nodeValue(Node node) {
    String value = node.isNull() ? null : node.getValue() == null ? "" : node.getValue();
    return new ScalarValue(value, node.getScalarKind());
  }

  private void mergeScalarKinds(
      String tableName, Map<String, List<ScalarValue>> valuesByName) {
    Map<String, ScalarKind> fields = scalarKindsByTable.computeIfAbsent(
        tableName, ignored -> new LinkedHashMap<>());
    for (Map.Entry<String, List<ScalarValue>> field : valuesByName.entrySet()) {
      ScalarKind kind = null;
      for (ScalarValue value : field.getValue()) {
        if (value.value() != null) {
          kind = ScalarKind.merge(kind, value.kind());
        }
      }
      if (kind != null) {
        fields.merge(field.getKey(), kind, ScalarKind::merge);
      }
    }
  }

  private ScalarKind fieldKind(String tableName, String fieldName) {
    return scalarKindsByTable.getOrDefault(tableName, Map.of())
        .getOrDefault(fieldName, ScalarKind.STRING);
  }

  private ScalarKind identityKind(String tableName) {
    return Boolean.TRUE.equals(sourceIdentityByTable.get(tableName))
        ? fieldKind(tableName, SOURCE_ID_COLUMN)
        : ScalarKind.STRING;
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

  private record ScalarValue(String value, ScalarKind kind) {
    Object databaseValue(ScalarKind targetKind) {
      if (value == null) return null;
      return switch (targetKind) {
        case STRING -> value;
        case NUMBER -> new BigDecimal(value);
        case BOOLEAN -> Boolean.valueOf(value);
      };
    }
  }

  private final class TableState {
    private final String logicalName;
    private final String renderedName;
    private final boolean valueTable;
    private final boolean sourceIdentity;
    private final Map<String, String> columnsByLogicalName = new LinkedHashMap<>();
    private final Map<String, FieldStorageType> fieldStorageTypesByLogicalName = new LinkedHashMap<>();
    private final List<Map<String, Object>> pendingRows = new ArrayList<>();
    private int nextGeneratedId = 1;
    private boolean created = false;

    private TableState(String logicalName, boolean valueTable, boolean sourceIdentity) {
      this.logicalName = logicalName;
      this.renderedName = CacheSqlIdentifier.render(logicalName);
      this.valueTable = valueTable;
      this.sourceIdentity = sourceIdentity;
    }

    private Object writeObjectRow(
        String parentTable,
        Object parentId,
        Map<String, List<ScalarValue>> valuesByName,
        Set<String> repeatedNames) {

      ScalarKind rowIdKind = identityKind(logicalName);
      ScalarValue sourceId = firstScalarValue(valuesByName, SOURCE_ID_COLUMN);
      Object rowId = sourceIdentity
          ? sourceId.databaseValue(rowIdKind)
          : nextId();

      ensureColumn(rowIdColumn(), rowIdKind);
      String parentColumn = parentTable == null ? null : parentIdColumn(parentTable);
      if (parentColumn != null) {
        ensureColumn(parentColumn, identityKind(parentTable));
      }

      for (Map.Entry<String, List<ScalarValue>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (isStructuralColumn(fieldName, parentColumn)) {
          continue;
        }
        prepareFieldStorage(
            fieldName, repeatedNames.contains(fieldName), fieldKind(logicalName, fieldName));
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(rowIdColumn(), rowId);
      if (parentColumn != null) {
        ScalarValue explicitParent = firstScalarValue(valuesByName, parentColumn);
        row.put(parentColumn, explicitParent == null || explicitParent.value() == null
            ? parentId
            : explicitParent.databaseValue(identityKind(parentTable)));
      }

      for (Map.Entry<String, List<ScalarValue>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (isStructuralColumn(fieldName, parentColumn)) {
          continue;
        }
        if (fieldStorageTypesByLogicalName.get(fieldName) == FieldStorageType.COLUMN) {
          ScalarValue value = firstScalarValue(valuesByName, fieldName);
          row.put(fieldName, value == null
              ? null
              : value.databaseValue(fieldKind(logicalName, fieldName)));
        }
      }

      pendingRows.add(row);
      flushIfFull();

      for (Map.Entry<String, List<ScalarValue>> field : valuesByName.entrySet()) {
        String fieldName = field.getKey();
        if (!isStructuralColumn(fieldName, parentColumn)
            && fieldStorageTypesByLogicalName.get(fieldName) == FieldStorageType.VALUE_TABLE) {
          writeRepeatedValueRows(rowId, fieldName, field.getValue());
        }
      }

      return rowId;
    }

    private void writeRepeatedValueRows(
        Object parentId, String fieldName, List<ScalarValue> values) {
      for (ScalarValue value : values) {
        writeRepeatedValueRow(parentId, fieldName, value);
      }
    }

    private void writeRepeatedValueRow(
        Object parentId, String fieldName, ScalarValue value) {
      ScalarKind valueKind = fieldKind(logicalName, fieldName);
      TableState valueTableState = ensureValueTable(fieldName, valueKind);
      valueTableState.writeValueTableRow(logicalName, parentId, value, valueKind);
    }

    private void writeValueTableRow(
        String parentTable, Object parentId, ScalarValue value, ScalarKind valueKind) {
      ensureColumn(rowIdColumn(), ScalarKind.STRING);
      ensureColumn(parentIdColumn(parentTable), identityKind(parentTable));
      ensureColumn("value", valueKind);

      Map<String, Object> row = new LinkedHashMap<>();
      row.put(rowIdColumn(), nextId());
      row.put(parentIdColumn(parentTable), parentId);
      row.put("value", value.databaseValue(valueKind));
      pendingRows.add(row);
      flushIfFull();
    }

    private void prepareFieldStorage(
        String fieldName, boolean repeated, ScalarKind scalarKind) {
      FieldStorageType storageType = fieldStorageTypesByLogicalName.get(fieldName);
      if (storageType == FieldStorageType.VALUE_TABLE) {
        ensureValueTable(fieldName, scalarKind);
        return;
      }
      if (storageType == FieldStorageType.COLUMN && repeated) {
        promoteField(fieldName, scalarKind);
        return;
      }
      if (storageType == null && repeated) {
        fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.VALUE_TABLE);
        ensureValueTable(fieldName, scalarKind);
        return;
      }
      if (storageType == null) {
        fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.COLUMN);
      }
      ensureColumn(fieldName, scalarKind);
    }

    private void promoteField(String fieldName, ScalarKind scalarKind) {
      flush();
      ensureValueTable(fieldName, scalarKind);

      String renderedFieldColumn = columnsByLogicalName.get(fieldName);
      if (renderedFieldColumn != null) {
        String select = "select "
            + columnsByLogicalName.get(rowIdColumn()) + " as \"cache_parent_id\", "
            + renderedFieldColumn + " as \"cache_field_value\" "
            + "from " + renderedName + " "
            + "where " + renderedFieldColumn + " is not null";

        try (SqlRowCursor rows = sqlExecutor.query(select)) {
          while (rows.next()) {
            writeRepeatedValueRow(
                rows.row().getValue("cache_parent_id"),
                fieldName,
                new ScalarValue(
                    rows.row().getStringValue("cache_field_value"), scalarKind));
          }
        }

        columnsByLogicalName.remove(fieldName);
        sqlExecutor.execute("alter table " + renderedName + " drop column " + renderedFieldColumn);
      }
      fieldStorageTypesByLogicalName.put(fieldName, FieldStorageType.VALUE_TABLE);
    }

    private TableState ensureValueTable(String fieldName, ScalarKind valueKind) {
      String valueTableName = repeatedTableName(logicalName, fieldName);
      TableState valueTableState = table(
          valueTableName,
          true);
      valueTableState.ensureColumn(valueTableState.rowIdColumn(), ScalarKind.STRING);
      valueTableState.ensureColumn(parentIdColumn(logicalName), identityKind(logicalName));
      valueTableState.ensureColumn("value", valueKind);
      return valueTableState;
    }

    private void ensureColumn(String logicalColumnName, ScalarKind scalarKind) {
      if (columnsByLogicalName.containsKey(logicalColumnName)) {
        return;
      }

      if (!created) {
        String renderedColumnName = CacheSqlIdentifier.render(logicalColumnName);
        columnsByLogicalName.put(logicalColumnName, renderedColumnName);
        sqlExecutor.execute(
            "create table " + renderedName
                + " (" + renderedColumnName + " " + sqlType(scalarKind) + ")");
        created = true;
        return;
      }

      flush();
      String renderedColumnName = CacheSqlIdentifier.render(logicalColumnName);
      columnsByLogicalName.put(logicalColumnName, renderedColumnName);
      sqlExecutor.execute(
          "alter table " + renderedName
              + " add column " + renderedColumnName + " " + sqlType(scalarKind));
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
      String insert = "insert into " + renderedName
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

  private static ScalarValue firstScalarValue(
      Map<String, List<ScalarValue>> valuesByName,
      String fieldName) {
    List<ScalarValue> values = valuesByName.get(fieldName);
    if (values == null || values.isEmpty()) {
      return null;
    }
    return values.getFirst();
  }

  private static String sqlType(ScalarKind scalarKind) {
    return switch (scalarKind) {
      case STRING -> "varchar";
      case NUMBER -> "decfloat";
      case BOOLEAN -> "boolean";
    };
  }
}
