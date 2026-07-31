package blater.nq.domain;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.domain.QueryResultRow;
import blater.nq.util.Log;
import lombok.Getter;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static blater.nq.domain.Node.isNull;

/*
 * Responsibility: Maps ordered query rows into one Node hierarchy.
 *
 * Plans are registered before rows are accepted. Registration creates
 * the root from the first contributing plan and validates that later
 * plans share it, so a zero-row contributing select still emits the
 * root and still participates in root checks.
 *
 */
public class Hierarchy {
  public enum RootKind {
    NAMED,
    SYNTHETIC_OBJECT,
    SYNTHETIC_ARRAY
  }

  /*
   * activeObjects records the latest Node opened or created for a path.
   * example:
   *     people.person          -> current person node
   *     people.person.address  -> current address
   */
  private final Map<HierarchyPath, Node> activePathEntries = new HashMap<>();
  private final IdentityHashMap<Node, Map<HierarchyPath, ChildBucket>> persistentChildren = new IdentityHashMap<>();
  private final Set<HierarchyPath> warnedInferredConflictPaths = new HashSet<>();

  @Getter // getRoot() is used by XmlOutputWriter to write output doc. Set by register();
  private Node root;
  @Getter private String namespace;
  @Getter private RootKind rootKind = RootKind.NAMED;

  private MappingPlan plan;

  public Hierarchy() {
  }

  public Hierarchy(Node root) {
    this.root = root;
    if (root != null && "".equals(root.getName())) {
      rootKind = RootKind.SYNTHETIC_ARRAY;
    }
  }

  public Hierarchy(Node root, RootKind rootKind) {
    this.root = root;
    this.rootKind = Objects.requireNonNull(rootKind);
  }

  public boolean hasNamespace() {
    return namespace != null && !namespace.isEmpty();
  }

  /*
   * Registers a plan's contribution to the tree root. Plans with no
   * root are ignored. The name is broad: this does root creation and
   * cross-plan root validation, but does not map any rows.
    // todo - join multiple plans with an implicit root support the multiple different branches on use-case
   */
  public void register(NestStatement stmt) {
    plan = stmt.getPlan();
    if (!stmt.isSelectProducingOutput()) {
      return;
    }

    if (this.root == null) {
      String rootName = stmt.getPlan().rootName() != null ? stmt.getPlan().rootName() : "";
      HierarchyPath rootPath = HierarchyPath.fromDottedPath(rootName);
      KeyedPath repeatedRoot = repeatedPath(rootPath);
      boolean flatRows = flatRows();
      boolean anonymousCollection = plan.getKeyedPaths().stream()
          .anyMatch(key -> key.inferred() && plan.repetitionPath(key) == null);
      if (flatRows || anonymousCollection) {
        root = new Node("");
        rootKind = RootKind.SYNTHETIC_ARRAY;
      } else if (repeatedRoot != null) {
        root = new Node("");
        rootKind = repeatedRoot.inferred() ? RootKind.SYNTHETIC_OBJECT : RootKind.SYNTHETIC_ARRAY;
      } else {
        root = new Node(rootName);
        rootKind = RootKind.NAMED;
        activePathEntries.put(rootPath, root);
      }
      initializeInferredCollections();
    }
    if (namespace == null)
      namespace = stmt.getNamespace();
  }

  @Deprecated
  public static HierarchyPath from(String dotSeparatedPath) {
    List<String> parts = Arrays.stream(dotSeparatedPath.split("\\."))
        .filter(part -> !part.isEmpty())
        .toList();
    return new HierarchyPath(parts);
  }

  /*
   * Maps one query row into the hierarchy for a registered plan. The
   * method first opens any repeated nodes required for this row, then
   * writes the row's mapped field values.
   */
  public boolean readRow(QueryResultRow row) {
    if (row == null)
      return false;

    RowContext rowContext = new RowContext();
    Node flatRow = null;
    if (flatRows()) {
      flatRow = new Node("");
      root.addNode(flatRow);
    }
    for (OutputField field : plan.getFields()) {
      if (!evaluateFieldConditions(field, row))
        continue;
      boolean nullValue = row.isNull(field.getSourceColumn());
      if (nullValue && field.isAbsentOnNull())
        continue;

      Node parent = field.getPath().parent() == null && flatRow != null
          ? flatRow
          : resolveParent(field.getPath().parent(), row, rowContext);
      if (parent == null)
        continue;
      KeyedPath terminalKey = keyedPath(field.getPath());
      if (terminalKey != null) {
        KeyState state = keyState(terminalKey, row);
        if (state == KeyState.ABSENT)
          continue;
        if (state == KeyState.PARTIAL && !terminalKey.inferred())
          throw new IllegalStateException("Partially null structure key: " + field.getPath());
        if (state == KeyState.PARTIAL)
          continue;
        writeTerminalKeyedValue(keyedChild(parent, field.getPath(), keyTuple(terminalKey, row)), field,
            row.getStringValue(field.getSourceColumn()), nullValue, terminalKey);
        continue;
      }
      writeResolvedValue(parent, field, row.getStringValue(field.getSourceColumn()), nullValue);
    }
    return true;
  }

  private Node resolveParent(HierarchyPath path, QueryResultRow row, RowContext rowContext) {
    if (path == null)
      return root;

    Node current = root;
    HierarchyPath currentPath = null;
    int start = 0;
    if (rootKind == RootKind.NAMED) {
      currentPath = HierarchyPath.fromDottedPath(path.getRootName());
      start = 1;
      if (path.isRoot()) return root;
    }
    for (int index = start; index < path.getPathParts().size(); index++) {
      currentPath = currentPath == null
          ? HierarchyPath.fromDottedPath(path.getPathParts().get(index))
          : currentPath.child(path.getPathParts().get(index));
      KeyedPath repeated = repeatedPath(currentPath);
      if (repeated == null && rootKind == RootKind.SYNTHETIC_ARRAY) {
        KeyedPath owner = keyedPath(currentPath);
        if (owner != null && plan.repetitionPath(owner) == null) {
          KeyState state = keyState(owner, row);
          if (state == KeyState.ABSENT) return null;
          Node item = state == KeyState.PARTIAL
              ? rowContext.anonymousChild(root, owner.path())
              : keyedAnonymousChild(root, owner.path(), keyTuple(owner, row));
          current = singletonChild(item, currentPath);
          continue;
        }
      }
      if (repeated != null) {
        KeyState state = keyState(repeated, row);
        if (state == KeyState.ABSENT)
          return null;
        if (state == KeyState.PARTIAL && !repeated.inferred())
          throw new IllegalStateException("Partially null structure key: " + currentPath);
        if (isInferredCollectionKey(repeated)) {
          Node collection = singletonChild(current, currentPath);
          collection.setCollection(true);
          current = state == KeyState.PARTIAL
              ? rowContext.anonymousChild(collection, repeated.path())
              : keyedAnonymousChild(collection, repeated.path(), keyTuple(repeated, row));
        } else if (state == KeyState.PARTIAL) {
          current = rowContext.objectChild(current, currentPath);
        } else {
          current = keyedChild(current, currentPath, keyTuple(repeated, row));
        }
      } else if (isInferredOwner(currentPath)) {
        current = singletonChild(current, currentPath);
      } else if (isObjectPath(currentPath)) {
        current = rowContext.objectChild(current, currentPath);
      } else {
        current = singletonChild(current, currentPath);
      }
    }
    return current;
  }

  private KeyedPath keyedPath(HierarchyPath path) {
    for (KeyedPath keyedPath : plan.getKeyedPaths()) {
      if (keyedPath.path().equals(path))
        return keyedPath;
    }
    return null;
  }

  private KeyedPath repeatedPath(HierarchyPath path) {
    for (KeyedPath keyedPath : plan.getKeyedPaths()) {
      if (path.equals(plan.repetitionPath(keyedPath))) return keyedPath;
    }
    return null;
  }

  private boolean isInferredOwner(HierarchyPath path) {
    KeyedPath key = keyedPath(path);
    return key != null && key.inferred() && !path.equals(plan.repetitionPath(key));
  }

  private boolean flatRows() {
    return !plan.getFields().isEmpty()
        && plan.getFields().stream().allMatch(field -> field.getPath().getPathParts().size() == 1);
  }

  private boolean isInferredCollectionKey(KeyedPath key) {
    return key.inferred() && !Objects.equals(key.path(), plan.repetitionPath(key));
  }

  private void initializeInferredCollections() {
    for (KeyedPath key : plan.getKeyedPaths()) {
      if (!isInferredCollectionKey(key)) continue;
      HierarchyPath collectionPath = plan.repetitionPath(key);
      if (collectionPath == null) {
        root.setCollection(true);
      } else {
        collectionNode(collectionPath).setCollection(true);
      }
    }
  }

  private Node collectionNode(HierarchyPath path) {
    Node current = root;
    int start = 0;
    HierarchyPath currentPath = null;
    if (rootKind == RootKind.NAMED) {
      currentPath = HierarchyPath.fromDottedPath(path.getRootName());
      start = 1;
      if (path.isRoot()) return root;
    }
    for (int index = start; index < path.getPathParts().size(); index++) {
      currentPath = currentPath == null
          ? HierarchyPath.fromDottedPath(path.getPathParts().get(index))
          : currentPath.child(path.getPathParts().get(index));
      current = singletonChild(current, currentPath);
    }
    return current;
  }

  private boolean isObjectPath(HierarchyPath path) {
    if (path.isRoot())
      return false;
    if (keyedPath(path) != null)
      return true;
    for (OutputField field : plan.getFields()) {
      if (path.equals(field.getPath().parent()))
        return true;
    }
    return false;
  }

  private Node singletonChild(Node parent, HierarchyPath path) {
    ChildBucket bucket = persistentBucket(parent, path);
    if (bucket.singleton == null) {
      bucket.singleton = new Node(path.getTerminalNodeName());
      parent.addNode(bucket.singleton);
    }
    return bucket.singleton;
  }

  private Node keyedChild(Node parent, HierarchyPath path, KeyTuple key) {
    ChildBucket bucket = persistentBucket(parent, path);
    return bucket.keyed.computeIfAbsent(key, ignored -> {
      Node child = new Node(path.getTerminalNodeName());
      parent.addNode(child);
      return child;
    });
  }

  private Node keyedAnonymousChild(Node parent, HierarchyPath path, KeyTuple key) {
    ChildBucket bucket = persistentBucket(parent, path);
    return bucket.keyed.computeIfAbsent(key, ignored -> {
      Node child = new Node("");
      parent.addNode(child);
      return child;
    });
  }

  private ChildBucket persistentBucket(Node parent, HierarchyPath path) {
    return persistentChildren
        .computeIfAbsent(parent, ignored -> new HashMap<>())
        .computeIfAbsent(path, ignored -> new ChildBucket());
  }

  private KeyState keyState(KeyedPath keyedPath, QueryResultRow row) {
    int nullCount = 0;
    for (String column : keyedPath.sourceColumns()) {
      if (row.isNull(column))
        nullCount++;
    }
    if (nullCount == keyedPath.sourceColumns().size())
      return KeyState.ABSENT;
    if (nullCount > 0)
      return KeyState.PARTIAL;
    return KeyState.PRESENT;
  }

  private KeyTuple keyTuple(KeyedPath keyedPath, QueryResultRow row) {
    return new KeyTuple(keyedPath.sourceColumns().stream()
        .map(column -> normalizeKeyValue(row.getValue(column)))
        .toList());
  }

  private Object normalizeKeyValue(Object value) {
    if (value instanceof BigDecimal decimal)
      return decimal.stripTrailingZeros();
    if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
      return BigDecimal.valueOf(((Number) value).longValue());
    if (value instanceof Float number) {
      if (!Float.isFinite(number))
        throw new IllegalArgumentException("Non-finite floating-point key value");
      return BigDecimal.valueOf(number.doubleValue());
    }
    if (value instanceof Double number) {
      if (!Double.isFinite(number))
        throw new IllegalArgumentException("Non-finite floating-point key value");
      return BigDecimal.valueOf(number);
    }
    if (value instanceof Boolean || value instanceof String || value instanceof UUID
        || value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime)
      return value;
    if (value instanceof Date date)
      return date.toLocalDate();
    if (value instanceof Time time)
      return time.toLocalTime();
    if (value instanceof Timestamp timestamp)
      return timestamp.toLocalDateTime();
    if (value == null)
      return null;
    throw new IllegalArgumentException("Unsupported structure key value type: " + value.getClass().getName());
  }

  private void writeResolvedValue(Node parent, OutputField field, String value, boolean nullValue) {
    String name = field.getPath().getTerminalNodeName();
    int existingIndex = lastChildIndex(parent, name);
    boolean attribute = field.isAttribute();

    if (field.getAppendText() != null) {
      if (nullValue)
        throw new IllegalStateException("append mapping requires absent on null for path: " + field.getPath());
      if (existingIndex < 0) {
        parent.addNode(valueNode(name, value, false, attribute));
      } else {
        Node existing = parent.getChildren().get(existingIndex);
        if (existing.isNull())
          throw new IllegalStateException("append mapping cannot combine a null value: " + field.getPath());
        parent.replaceChild(existingIndex, valueNode(
            name, existing.getValue() + field.getAppendText() + value, false, attribute));
      }
      return;
    }

    if (existingIndex < 0) {
      parent.addNode(valueNode(name, value, nullValue, attribute));
      return;
    }

    Node existing = parent.getChildren().get(existingIndex);
    if (existing.isNull() == nullValue && Objects.equals(existing.getValue(), value))
      return;
    KeyedPath inferredKey = nearestKeyedPath(field.getPath().parent());
    if (inferredKey != null && inferredKey.inferred()) {
      warnInferredConflict(field.getPath(), inferredKey);
      return;
    }
    throw new IllegalStateException("Conflicting values for output path: " + field.getPath());
  }

  private void writeTerminalKeyedValue(
      Node target,
      OutputField field,
      String value,
      boolean nullValue,
      KeyedPath keyedPath) {
    if (field.getAppendText() != null) {
      if (nullValue)
        throw new IllegalStateException("append mapping requires absent on null for path: " + field.getPath());
      if (Node.isNull(target)) {
        target.setValue(value);
        target.setNullValue(false);
      } else {
        target.setValue(target.getValue() + field.getAppendText() + value);
      }
      return;
    }
    if (Node.isNull(target)) {
      target.setValue(value);
      target.setNullValue(nullValue);
      target.setAttribute(field.isAttribute());
      return;
    }
    if (target.isNull() == nullValue && Objects.equals(target.getValue(), value))
      return;
    if (keyedPath.inferred()) {
      warnInferredConflict(field.getPath(), keyedPath);
      return;
    }
    throw new IllegalStateException("Conflicting values for output path: " + field.getPath());
  }

  private KeyedPath nearestKeyedPath(HierarchyPath path) {
    HierarchyPath current = path;
    while (current != null) {
      KeyedPath keyedPath = keyedPath(current);
      if (keyedPath != null) return keyedPath;
      current = current.parent();
    }
    return null;
  }

  private void warnInferredConflict(HierarchyPath valuePath, KeyedPath keyedPath) {
    if (!warnedInferredConflictPaths.add(keyedPath.path())) return;
    Log.warn(
        "Inferred structure key [{}] coalesced conflicting value [{}]; keeping the first value. Possible data loss.",
        keyedPath.sourceColumns(), String.join(".", valuePath.getPathParts()));
  }

  private final class RowContext {
    private final IdentityHashMap<Node, Map<HierarchyPath, Node>> objects = new IdentityHashMap<>();

    Node objectChild(Node parent, HierarchyPath path) {
      return objects.computeIfAbsent(parent, ignored -> new HashMap<>())
          .computeIfAbsent(path, ignored -> {
            Node child = new Node(path.getTerminalNodeName());
            parent.addNode(child);
            return child;
          });
    }

    Node anonymousChild(Node parent, HierarchyPath path) {
      return objects.computeIfAbsent(parent, ignored -> new HashMap<>())
          .computeIfAbsent(path, ignored -> {
            Node child = new Node("");
            parent.addNode(child);
            return child;
          });
    }
  }

  private static final class ChildBucket {
    Node singleton;
    final Map<KeyTuple, Node> keyed = new HashMap<>();
  }

  private enum KeyState { ABSENT, PARTIAL, PRESENT }

  private record KeyTuple(List<Object> values) {
    KeyTuple {
      values = List.copyOf(values);
    }
  }

  /*
   * Reports whether no contributing hierarchy plan has been registered.
   * The name is slightly misleading: the accumulator may still hold
   * namespace or attribute metadata.
   */
  public boolean isEmpty() {
    return root == null;
  }

  /*
   * Selects concrete nodes for a simple hierarchy path. Attribute paths
   * match only terminal attribute nodes; intermediary segments always
   * traverse element nodes.
   */
  public List<Node> select(HierarchyPath path) {
    if (root == null || path == null || path.getPathParts().isEmpty()) {
      return List.of();
    }
    if (!Objects.equals(root.getName(), path.getRootName())) {
      return List.of();
    }
    if (path.getPathParts().size() == 1) {
      return path.isAttribute() ? List.of() : List.of(root);
    }

    List<Node> matches = List.of(root);
    for (int index = 1; index < path.getPathParts().size(); index++) {
      String segment = path.getPathParts().get(index);
      boolean terminal = index == path.getPathParts().size() - 1;
      boolean attribute = terminal && path.isAttribute();
      matches = matchingChildren(matches, segment, attribute);
      if (matches.isEmpty()) {
        return List.of();
      }
    }
    return matches;
  }

  /*
   * Ensures the final segment of a simple path exists below each existing
   * parent match. Missing intermediary branches are not created, preserving
   * the current DML row-inference behavior.
   */
  public List<Node> ensureFinalTargets(HierarchyPath path, String defaultValue) {
    if (root == null || path == null || path.isRoot()) {
      return List.of();
    }
    List<Node> parents = select(path.parent());
    if (parents.isEmpty()) {
      return List.of();
    }

    List<Node> targets = new ArrayList<>();
    for (Node parent : parents) {
      Node target = firstChild(parent, path.getTerminalNodeName(), path.isAttribute());
      if (target == null) {
        target = valueNode(
            path.getTerminalNodeName(),
            defaultValue == null ? "" : defaultValue,
            false,
            path.isAttribute());
        parent.addNode(target);
      } else if (defaultValue != null && !target.hasValue()) {
        target.setValue(defaultValue);
      } else if (defaultValue != null && target.getValue() != null && target.getValue().isEmpty()) {
        target.setValue(defaultValue);
      }
      targets.add(target);
    }
    return targets;
  }

  /*
   * create a new node if key value changes (as requested by a
   * createsNew rule such as "people.id createsNew {people.person}")
   *
   * A createsNew rule on a value path, such as createsNew {people.person.name},
   * is not handled here because there is no child container to create. That case
   * is handled when the value is written, where it means "add another value node".
   */
  private void createNewNodeBlocksForIdChange(MappingPlan plan, QueryResultRow row) {
    for (CorrelationRule rule : plan.getCorrelationRules()) {
      if (isFieldPath(plan, rule.getPath())) {
        // value-reset rule, handled when the field is written
        continue;
      }
      if (!correlationRulesPass(rule, row)) {
        continue;
      }
      Node parent = findOrCreateNode(rule.getPath().parent());
      Node repeatedBlock = new Node(rule.getPath().getTerminalNodeName());
      // Node repeated = Node.builder().name(rule.getPath().getTerminalNodeName()).build();

      parent.addNode(repeatedBlock);
      // The entries for the last instance of this node block are in activePathEntries. Clear them out
      // note: implicit loop
      activePathEntries.keySet()
          .removeIf(path -> path.isBelow(rule.getPath()));
      // add the new container block
      activePathEntries.put(rule.getPath(), repeatedBlock);
    }
  }


  /*
   * Writes this row's mapped values into the hierarchy. Each field finds
   * or creates its parent node, then adds, replaces, or composes a value
   * node below it.
   */
  private void applyFields(MappingPlan plan, QueryResultRow row) {

    for (var field : plan.getFields()) {
      if (!evaluateFieldConditions(field, row))
        continue;

      var parent = findOrCreateNode(field.getPath().parent());
      var stringValue = row.getStringValue(field.getSourceColumn());
      var isNull = row.isNull(field.getSourceColumn());
      if (isNull && field.isAbsentOnNull())
        continue;

      boolean isNew = startsNewNode(plan, field.getPath(), row);
      writeValueNode(parent, field, stringValue, isNull, isNew);
    }
  }

  /*
   * Decides whether a field-level correlation rule fired for this row.
   * A fired rule starts a fresh value node instead of replacing or
   * composing with the previous value node.
   */
  private boolean startsNewNode(MappingPlan plan, HierarchyPath fieldPath, QueryResultRow row) {
    for (var rule : plan.getCorrelationRules()) {
      if (!rule.getPath().equals(fieldPath)) {
        continue;
      }
      if (correlationRulesPass(rule, row)) {
        return true;
      }
    }
    return false;
  }

  /*
   * Identifies correlation rules that target a value field rather than a
   * node. The name hides that this is used to separate value reset from
   * node creation.
   */
  private boolean isFieldPath(MappingPlan plan, HierarchyPath path) {
    for (var field : plan.getFields()) {
      if (field.getPath().equals(path)) {
        return true;
      }
    }
    return false;
  }

  /*
   * Returns the active Node for a path, creating any missing parent
   * nodes and making the created node active. This changes both the
   * output hierarchy and activeObjects.
   */
  private Node findOrCreateNode(HierarchyPath path) {
    if (path == null || path.isRoot()) {
      return root;
    }
    var active = activePathEntries.get(path);
    if (active != null) {
      return active;
    }

    var parent = findOrCreateNode(path.parent());
    var created = new Node(path.getTerminalNodeName());
    parent.addNode(created);
    activePathEntries.put(path, created);
    return created;
  }

  /*
   * Applies OutputField value-composition rules under a parent node.
   * appendText is the text inserted between the current value and the
   * next value when append(...) syntax targets the same value node.
   */
  private void writeValueNode(Node parent, OutputField field, String value, boolean nullValue, boolean isNew) {
    String name = field.getPath().getTerminalNodeName();
    int existingIndex = isNew ? -1 : lastChildIndex(parent, name);

    boolean attribute = field.isAttribute();
    if (field.getAppendText() != null) {
      Node existing = existingIndex < 0 ? null : parent.getChildren().get(existingIndex);
      if (isNull(existing))
        parent.addNode(valueNode(name, value, nullValue, attribute));
      else {
        String combined = existing.getValue() + field.getAppendText() + value;
        parent.replaceChild(existingIndex, valueNode(name, combined, nullValue, attribute));
      }
    } else {
      var newNode = valueNode(name, value, nullValue, attribute);
      if (existingIndex < 0) {
        parent.addNode(newNode);
      } else {
        parent.replaceChild(existingIndex, newNode);
      }
    }
  }

  private List<Node> matchingChildren(List<Node> parents, String name, boolean attribute) {
    List<Node> matches = new ArrayList<>();
    for (Node parent : parents) {
      for (Node child : parent.getChildren()) {
        if (Objects.equals(child.getName(), name) && child.isAttribute() == attribute) {
          matches.add(child);
        }
      }
    }
    return matches;
  }

  private Node firstChild(Node parent, String name, boolean attribute) {
    for (Node child : parent.getChildren()) {
      if (Objects.equals(child.getName(), name) && child.isAttribute() == attribute) {
        return child;
      }
    }
    return null;
  }

  /*
   * Finds the latest child with this name. The latest child is the one
   * mapping updates because repeated paths are appended in output order.
   */
  private int lastChildIndex(Node parent, String name) {
    int childIndex = -1;

    for (int idx = parent.getChildren().size() - 1; idx >= 0; idx--) {
      if (parent.getChildren().get(idx).getName().equals(name)) {
        childIndex = idx;
        break;
      }
    }
    return childIndex;
  }

  private Node valueNode(String name, String value, boolean nullValue, boolean attribute) {
    var node = new Node(name);
    node.setValue(value);
    node.setNullValue(nullValue);
    node.setAttribute(attribute);
    return node;
  }

  /*
   * Checks whether all field-specific conditions match this row.
   */
  private boolean evaluateFieldConditions(OutputField field, QueryResultRow row) {
    for (var condition : field.getConditions()) {
      if (!Evaluator.evaluate(condition, row)) {
        return false;
      }
    }
    return true;
  }

  /*
   * Checks whether all node-opening or field-reset conditions match
   * this row.
   */
  private boolean correlationRulesPass(CorrelationRule rule, QueryResultRow row) {
    for (var condition : rule.getConditions()) {
      if (!Evaluator.evaluate(condition, row)) {
        return false;
      }
    }
    return true;
  }

}
