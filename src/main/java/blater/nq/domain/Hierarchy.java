package blater.nq.domain;

import blater.nq.parser.script.NestStatement;
import blater.nq.runner.sql.domain.QueryResultRow;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/** Accumulates mapped query rows and exposes navigation over the resulting node tree. */
public class Hierarchy {
  public enum RootKind {
    NAMED,
    SYNTHETIC_OBJECT,
    SYNTHETIC_ARRAY
  }

  private final HierarchyNodeResolver nodeResolver = new HierarchyNodeResolver();
  private final HierarchyRowMapper rowMapper = new HierarchyRowMapper(nodeResolver);

  @Getter
  private Node root;
  @Getter
  private String namespace;
  @Getter
  private RootKind rootKind = RootKind.NAMED;
  private MappingPlan plan;

  public Hierarchy() {
  }

  public Hierarchy(Node root) {
    this.root = root;
    if (root != null && root.getName().isEmpty()) {
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

  /** Registers a plan, creating its output root before any rows are read. */
  public void register(NestStatement statement) {
    plan = statement.getPlan();
    if (!statement.isSelectProducingOutput()) {
      return;
    }
    if (root == null) {
      createRoot(plan);
      nodeResolver.initializeInferredCollections(root, rootKind, plan);
    }
    if (namespace == null) {
      namespace = statement.getNamespace();
    }
  }

  /** Maps one query row using the most recently registered plan. */
  public boolean readRow(QueryResultRow row) {
    return rowMapper.readRow(root, rootKind, plan, row);
  }

  public boolean isEmpty() {
    return root == null;
  }

  public List<Node> select(HierarchyPath path) {
    return HierarchyNavigator.select(root, path);
  }

  public List<Node> ensureFinalTargets(HierarchyPath path, String defaultValue) {
    return HierarchyNavigator.ensureFinalTargets(root, path, defaultValue);
  }

  private void createRoot(MappingPlan mappingPlan) {
    String rootName = mappingPlan.rootName() == null ? "" : mappingPlan.rootName();
    HierarchyPath rootPath = HierarchyPath.fromDottedPath(rootName);
    KeyedPath repeatedRoot = nodeResolver.repeatedPath(mappingPlan, rootPath);
    boolean anonymousCollection = mappingPlan.getKeyedPaths().stream()
        .anyMatch(key -> key.placement() instanceof RepetitionPlacement.AnonymousItem anonymous
            && anonymous.containerPath().isEmpty());
    if (nodeResolver.flatRows(mappingPlan) || anonymousCollection) {
      root = new Node("");
      rootKind = RootKind.SYNTHETIC_ARRAY;
    } else if (repeatedRoot != null) {
      root = new Node("");
      rootKind = repeatedRoot.placement() instanceof RepetitionPlacement.AnonymousItem
          ? RootKind.SYNTHETIC_OBJECT
          : RootKind.SYNTHETIC_ARRAY;
    } else {
      root = new Node(rootName);
      rootKind = RootKind.NAMED;
    }
  }
}
