package blater.nq.runner.sql.cache;

import blater.nq.domain.Node;
import blater.nq.inputreader.InputDocument;
import blater.nq.inputreader.RelationPath;
import blater.nq.inputreader.SourceStructure;
import blater.nq.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class RelationMaterializationPlan {
  static final StorageKey ANONYMOUS_ITEM_STORAGE = new StorageKey("anonymous:item");

  record StorageKey(String value) { }

  record PlannedRelation(
      RelationPath path,
      StorageKey storageKey,
      String logicalName,
      NamingOrigin origin,
      boolean anonymous) { }

  enum NamingOrigin {
    ALIAS("explicit alias"),
    DECLARED("declared source name"),
    FORMAT("format rule"),
    ANONYMOUS("anonymous fallback");

    private final String display;

    NamingOrigin(String display) {
      this.display = display;
    }
  }

  private final IdentityHashMap<Node, PlannedRelation> byNode;
  private final Map<RelationPath, PlannedRelation> byPath;

  private RelationMaterializationPlan(
      IdentityHashMap<Node, PlannedRelation> byNode,
      Map<RelationPath, PlannedRelation> byPath) {
    this.byNode = byNode;
    this.byPath = Map.copyOf(byPath);
  }

  PlannedRelation relation(Node node) {
    PlannedRelation relation = byNode.get(node);
    if (relation == null) {
      return Log.fatal(IllegalStateException.class,
          "No materialization relation was planned for source node [" + node.getName() + "].");
    }
    return relation;
  }

  static RelationMaterializationPlan create(
      InputDocument document,
      MaterializationConfiguration configuration) {
    Discovery discovery = new Discovery(document);
    discovery.discover();
    return discovery.plan(configuration);
  }

  private static final class Draft {
    final RelationPath path;
    final Optional<String> declaredName;
    final boolean anonymous;
    final List<Node> rows = new ArrayList<>();
    String logicalName;
    StorageKey storageKey;
    NamingOrigin origin;

    Draft(RelationPath path, Optional<String> declaredName, boolean anonymous) {
      this.path = path;
      this.declaredName = declaredName;
      this.anonymous = anonymous;
    }
  }

  private static final class Discovery {
    private final InputDocument document;
    private final Map<RelationPath, Draft> drafts = new LinkedHashMap<>();
    private final IdentityHashMap<Node, Draft> draftByNode = new IdentityHashMap<>();

    Discovery(InputDocument document) {
      this.document = document;
    }

    void discover() {
      Node root = document.hierarchy().getRoot();
      if (root != null) discoverNode(root, true, fallbackRootPath(root));
      for (SourceStructure.CollectionMetadata collection : document.sourceStructure().collections().values()) {
        if (collection.empty()) {
          drafts.computeIfAbsent(collection.path(), ignored -> new Draft(
              collection.path(), collection.declaredName(), collection.anonymous()));
        }
      }
    }

    void discoverNode(Node node, boolean root, RelationPath fallbackPath) {
      List<Node> objectChildren = node.getChildren().stream()
          .filter(child -> !child.isAttribute() && !scalar(child))
          .toList();
      boolean hasScalarChildren = node.getChildren().stream().anyMatch(RelationMaterializationPlan::scalar);
      boolean emptyObject = !root && !node.hasValue() && node.getChildren().isEmpty();
      boolean materialized = !node.isAttribute()
          && (hasScalarChildren || (!root && (!objectChildren.isEmpty() || emptyObject)));

      RelationPath nodePath = document.sourceStructure().metadata(node)
          .map(SourceStructure.NodeMetadata::valuePath)
          .orElse(fallbackPath);
      if (materialized) {
        SourceStructure.NodeMetadata metadata = document.sourceStructure().metadata(node).orElse(null);
        RelationPath relationPath = metadata != null && metadata.owningCollection().isPresent()
            ? metadata.owningCollection().get() : nodePath;
        SourceStructure.CollectionMetadata collection = metadata != null && metadata.owningCollection().isPresent()
            ? document.sourceStructure().collections().get(relationPath) : null;
        Optional<String> declared = collection != null
            ? collection.declaredName()
            : metadata == null ? Optional.ofNullable(node.getName()) : metadata.declaredName();
        boolean anonymous = collection != null && collection.anonymous();
        Draft draft = drafts.computeIfAbsent(relationPath,
            ignored -> new Draft(relationPath, declared, anonymous));
        draft.rows.add(node);
        draftByNode.put(node, draft);
      }

      for (Node child : objectChildren) {
        RelationPath childFallback = nodePath.member(child.getName() == null ? "" : child.getName());
        discoverNode(child, false, childFallback);
      }
    }

    RelationMaterializationPlan plan(MaterializationConfiguration configuration) {
      Map<RelationPath, MaterializationConfiguration.RelationAlias> aliases = resolveAliases(configuration);
      List<Draft> anonymousFallbacks = new ArrayList<>();
      for (Draft draft : drafts.values()) {
        MaterializationConfiguration.RelationAlias alias = aliases.get(draft.path);
        if (alias != null) {
          draft.logicalName = alias.logicalName();
          draft.origin = NamingOrigin.ALIAS;
          draft.storageKey = new StorageKey("path:" + typedIdentity(draft.path));
        } else if (draft.anonymous) {
          draft.logicalName = "item";
          draft.origin = NamingOrigin.ANONYMOUS;
          draft.storageKey = ANONYMOUS_ITEM_STORAGE;
          anonymousFallbacks.add(draft);
        } else {
          draft.logicalName = draft.declaredName.filter(name -> !name.isEmpty()).orElseGet(() ->
              draft.path.leafName().isEmpty() ? formatName(document) : draft.path.leafName());
          draft.origin = draft.declaredName.isPresent() ? NamingOrigin.DECLARED : NamingOrigin.FORMAT;
          draft.storageKey = new StorageKey("path:" + typedIdentity(draft.path));
        }
      }

      anonymousFallbacks.sort(Comparator.comparing(draft -> draft.path));
      if (anonymousFallbacks.size() > 1) {
        String paths = anonymousFallbacks.stream().map(draft -> draft.path.display())
            .collect(Collectors.joining("] and [", "[", "]"));
        if (configuration.anonymousMode()
            == MaterializationConfiguration.AnonymousCollectionMode.ERROR) {
          Log.fatal(IllegalArgumentException.class,
              "Anonymous relations " + paths + " cannot share [item] under "
                  + "--anonymous-collections=error. Assign names with --relation-alias or use "
                  + "--anonymous-collections=merge.");
        }
        StringBuilder suggestion = new StringBuilder();
        for (int index = 1; index < anonymousFallbacks.size(); index++) {
          if (!suggestion.isEmpty()) suggestion.append(' ');
          suggestion.append("--relation-alias '")
              .append(anonymousFallbacks.get(index).path.display())
              .append("=anonymous_").append(index + 1).append("'");
        }
        Log.warn("Anonymous relations " + paths + " are being merged into [item]; rows will not "
            + "retain query-visible provenance. Assign names with " + suggestion + ".");
      }

      validateTableIdentities();
      validateDerivedIdentities();

      IdentityHashMap<Node, PlannedRelation> byNode = new IdentityHashMap<>();
      Map<RelationPath, PlannedRelation> byPath = new LinkedHashMap<>();
      for (Draft draft : drafts.values()) {
        PlannedRelation relation = new PlannedRelation(
            draft.path, draft.storageKey, draft.logicalName, draft.origin, draft.anonymous);
        byPath.put(draft.path, relation);
        for (Node node : draft.rows) byNode.put(node, relation);
        Log.debug("Relation [" + draft.path + "] -> [" + draft.logicalName + "] via "
            + draft.origin.display);
      }
      return new RelationMaterializationPlan(byNode, byPath);
    }

    Map<RelationPath, MaterializationConfiguration.RelationAlias> resolveAliases(
        MaterializationConfiguration configuration) {
      Map<RelationPath, MaterializationConfiguration.RelationAlias> result = new LinkedHashMap<>();
      for (MaterializationConfiguration.RelationAlias alias : configuration.aliases()) {
        List<RelationPath> matches = drafts.keySet().stream().filter(alias.source()::matches).toList();
        if (matches.size() != 1) {
          Log.fatal(IllegalArgumentException.class,
              matches.isEmpty()
                  ? "Relation alias path [" + alias.source() + "] does not match a source relation."
                  : "Relation alias path [" + alias.source() + "] is ambiguous.");
        }
        result.put(matches.getFirst(), alias);
      }
      return result;
    }

    void validateTableIdentities() {
      Map<String, Draft> identities = new LinkedHashMap<>();
      for (Draft draft : sortedDrafts()) {
        String identity = CacheIdentifierNaming.sqlIdentity(draft.logicalName);
        Draft previous = identities.putIfAbsent(identity, draft);
        if (previous != null && !previous.storageKey.equals(draft.storageKey)) {
          Log.fatal(IllegalArgumentException.class,
              "Relation name [" + previous.logicalName + "] is requested by ["
                  + previous.path + "] and [" + draft.path + "]. Assign a stable name with "
                  + "--relation-alias, for example --relation-alias '" + draft.path
                  + "=" + aliasSuggestion(draft.path) + "'.");
        }
      }
    }

    void validateDerivedIdentities() {
      Map<String, String> identities = new LinkedHashMap<>();
      for (Draft draft : sortedDrafts()) {
        identities.put(CacheIdentifierNaming.sqlIdentity(draft.logicalName), draft.path.display());
      }
      Set<String> seenDerived = new LinkedHashSet<>();
      for (Draft draft : sortedDrafts()) {
        Map<String, Boolean> repeated = new LinkedHashMap<>();
        for (Node row : draft.rows) {
          Map<String, Integer> counts = new LinkedHashMap<>();
          for (Node child : row.getChildren()) {
            if (scalar(child)) {
              counts.merge(child.getName(), 1, Integer::sum);
              if (child.isArrayItem()) repeated.put(child.getName(), true);
            }
          }
          counts.forEach((name, count) -> {
            if (count > 1) repeated.put(name, true);
          });
        }
        for (String field : repeated.keySet()) {
          String logical = draft.logicalName + "_" + field;
          String identity = CacheIdentifierNaming.sqlIdentity(logical);
          if (!seenDerived.add(draft.storageKey.value() + ":" + field)) continue;
          String previous = identities.putIfAbsent(identity, draft.path + "#" + field);
          if (previous != null) {
            Log.fatal(IllegalArgumentException.class,
                "Derived value relation [" + logical + "] for [" + draft.path + "] collides with ["
                    + previous + "]. Alias the owning relation or the other source relation.");
          }
        }
      }
    }

    private List<Draft> sortedDrafts() {
      return drafts.values().stream()
          .sorted(Comparator.comparing(draft -> draft.path))
          .toList();
    }

    private RelationPath fallbackRootPath(Node root) {
      if (root.getName() == null || root.getName().isEmpty()) return RelationPath.root();
      return RelationPath.root().member(root.getName());
    }

    private String formatName(InputDocument ignored) {
      Node root = document.hierarchy().getRoot();
      return root == null || root.getName() == null || root.getName().isEmpty() ? "item" : root.getName();
    }

    private static String typedIdentity(RelationPath path) {
      return path.segments().stream().map(segment -> segment.getClass().getSimpleName() + ":" + segment)
          .collect(Collectors.joining("/"));
    }

    private static String aliasSuggestion(RelationPath path) {
      String value = path.display().substring(1).replace("/*/", "_").replace('/', '_')
          .replaceAll("[^A-Za-z0-9_]", "_");
      if (value.isEmpty() || !Character.isLetter(value.charAt(0)) && value.charAt(0) != '_') {
        value = "relation_" + value;
      }
      return value;
    }
  }

  private static boolean scalar(Node node) {
    if (node.isAttribute()) return true;
    return node.hasValue() && node.getChildren().stream().noneMatch(child -> !child.isAttribute());
  }
}
