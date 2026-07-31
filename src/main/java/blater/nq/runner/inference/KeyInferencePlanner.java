package blater.nq.runner.inference;

import blater.nq.domain.HierarchyPath;
import blater.nq.parser.script.SelectBlueprint;
import blater.nq.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies a cached database graph to one parsed DQL SELECT. */
public final class KeyInferencePlanner {
  private static final Pattern RELATION = Pattern.compile(
      "(?i)\\b(?:from|join)\\s+((?:\\\"[^\\\"]+\\\"|[a-z0-9_$]+)(?:\\.(?:\\\"[^\\\"]+\\\"|[a-z0-9_$]+))*)"
          + "(?:\\s+(?:as\\s+)?(\\\"[^\\\"]+\\\"|[a-z_][a-z0-9_$]*))?");
  private static final Pattern COLUMN = Pattern.compile(
      "^\\s*(\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$]*)\\s*\\.\\s*"
          + "(\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$]*)\\s*$");
  private static final Pattern UNQUALIFIED_COLUMN = Pattern.compile(
      "^\\s*(\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$]*)\\s*$");
  private static final Pattern COMMA_RELATION = Pattern.compile(
      "^\\s*((?:\\\"[^\\\"]+\\\"|[a-zA-Z0-9_$]+)(?:\\.(?:\\\"[^\\\"]+\\\"|[a-zA-Z0-9_$]+))*)"
          + "(?:\\s+(?:as\\s+)?(\\\"[^\\\"]+\\\"|[a-zA-Z_][a-zA-Z0-9_$]*))?.*$",
      Pattern.CASE_INSENSITIVE);
  private static final Set<String> SQL_KEYWORDS = Set.of(
      "where", "inner", "left", "right", "full", "cross", "join", "on", "group", "having",
      "order", "union", "limit", "offset", "fetch");

  public CompiledSelect compile(SelectBlueprint blueprint, DatabaseStructure structure) {
    if (blueprint == null) {
      throw new IllegalArgumentException("A SELECT blueprint is required for key inference.");
    }
    if (isDistinct(blueprint) || isAggregateWithoutGroup(blueprint)) {
      SelectBlueprint.Compiled existing = blueprint.compile(List.of());
      Log.debug("DQL key inference: no inferred relationships used (DISTINCT or ungrouped aggregate query).");
      return new CompiledSelect(existing.sql(), existing.plan());
    }

    Map<Integer, List<RelationOccurrence>> occurrencesByBranch = new LinkedHashMap<>();
    for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
      occurrencesByBranch.put(branchIndex, relationOccurrences(
          blueprint.branches().get(branchIndex).sqlTail(), structure));
    }
    if (occurrencesByBranch.values().stream().allMatch(List::isEmpty)) {
      SelectBlueprint.Compiled existing = blueprint.compile(List.of());
      Log.debug("DQL key inference: no inferred relationships used (no metadata relations were bound).");
      return new CompiledSelect(existing.sql(), existing.plan());
    }

    Set<HierarchyPath> explicitPaths = new HashSet<>();
    blueprint.explicitKeys().forEach(key -> explicitPaths.add(key.path()));
    List<HierarchyPath> paths = blueprint.objectPaths().stream()
        .sorted(Comparator.comparingInt(path -> path.getPathParts().size()))
        .toList();

    List<SelectBlueprint.StructureKey> inferred = new ArrayList<>();
    List<String> inferenceUsage = new ArrayList<>();
    Map<Integer, Map<HierarchyPath, RelationOccurrence>> ownersByBranch = new HashMap<>();
    for (HierarchyPath path : paths) {
      if (explicitPaths.contains(path)) {
        continue;
      }
      Map<Integer, List<String>> expressionsByBranch = new LinkedHashMap<>();
      Map<Integer, String> usageByBranch = new LinkedHashMap<>();
      for (int branchIndex = 0; branchIndex < blueprint.branches().size(); branchIndex++) {
        SelectBlueprint.Branch branch = blueprint.branches().get(branchIndex);
        if (!branch.mapsPath(path)) continue;
        List<String> grouping = groupingExpressions(branch.sqlTail());
        if (!grouping.isEmpty()) {
          expressionsByBranch.put(branchIndex, grouping);
          usageByBranch.put(branchIndex, formatGroupingUsage(
              path, branchIndex, blueprint.branches().size(), grouping));
          continue;
        }
        List<RelationOccurrence> occurrences = occurrencesByBranch.get(branchIndex);
        List<BoundItem> boundItems = boundItemsFor(path, branch.items(), occurrences);
        RelationOccurrence parent = ownersByBranch
            .computeIfAbsent(branchIndex, ignored -> new HashMap<>())
            .get(path.parent());
        List<ScoredOccurrence> candidates = scoreCandidates(
            path, boundItems, occurrences, parent, structure);
        if (candidates.isEmpty()) continue;
        ScoredOccurrence winner = candidates.getFirst();
        if (candidates.size() > 1 && winner.score() == candidates.get(1).score()) {
          Log.warn(
              "Ambiguous key inference for output path [{}] branch [{}]; selected [{}] over [{}]. Possible data loss; add an explicit structure key or use --no-key-inference.",
              dotted(path), branchIndex + 1, winner.occurrence().alias(), candidates.get(1).occurrence().alias());
        }
        DatabaseStructure.CandidateKey key = winner.occurrence().relation().preferredKey().orElse(null);
        if (key == null || key.columns().isEmpty()) continue;
        ownersByBranch.get(branchIndex).put(path, winner.occurrence());
        expressionsByBranch.put(branchIndex, key.columns().stream()
            .map(column -> winner.occurrence().sqlAlias() + "." + column)
            .toList());
        usageByBranch.put(branchIndex, formatMetadataUsage(
            path,
            branchIndex,
            blueprint.branches().size(),
            winner.occurrence(),
            key,
            parent,
            relationshipsBetween(parent, winner.occurrence(), structure)));
      }
      if (expressionsByBranch.isEmpty()) continue;
      long mappedBranches = blueprint.branches().stream().filter(branch -> branch.mapsPath(path)).count();
      if (expressionsByBranch.size() != mappedBranches) {
        Log.warn(
            "Could not infer a key for every hierarchy-union branch at output path [{}]; preserving row-first behavior for that path.",
            dotted(path));
        continue;
      }
      int arity = expressionsByBranch.values().iterator().next().size();
      if (expressionsByBranch.values().stream().anyMatch(expressions -> expressions.size() != arity)) {
        Log.warn(
            "Could not infer a compatible hierarchy-union key for output path [{}]; preserving row-first behavior for that path.",
            dotted(path));
        continue;
      }
      if (blueprint.branches().size() == 1) {
        inferred.add(SelectBlueprint.StructureKey.inferred(
            path, expressionsByBranch.values().iterator().next()));
      } else {
        Map<Integer, List<String>> branchLocalKeys = new LinkedHashMap<>();
        expressionsByBranch.forEach((branch, expressions) -> {
          List<String> values = new ArrayList<>();
          values.add("'select_branch_" + branch + "'");
          values.addAll(expressions);
          branchLocalKeys.put(branch, values);
        });
        inferred.add(SelectBlueprint.StructureKey.inferredBranches(path, branchLocalKeys));
      }
      inferenceUsage.addAll(usageByBranch.values());
    }

    SelectBlueprint.Compiled compiled = blueprint.compile(inferred);
    logInferenceUsage(inferenceUsage);
    return new CompiledSelect(compiled.sql(), compiled.plan());
  }

  public static List<String> referencedRelations(SelectBlueprint blueprint) {
    if (blueprint == null) return List.of();
    Set<String> names = new java.util.LinkedHashSet<>();
    for (SelectBlueprint.Branch branch : blueprint.branches()) {
      if (branch.sqlTail() == null) continue;
      Matcher matcher = RELATION.matcher(branch.sqlTail());
      while (matcher.find()) names.add(unquoteQualified(matcher.group(1)));
      String lower = branch.sqlTail().toLowerCase(Locale.ROOT);
      int from = lower.indexOf("from ");
      if (from >= 0) {
        int end = branch.sqlTail().length();
        for (String terminator : List.of(" where ", " group ", " having ", " order ", " limit ", " offset ")) {
          int found = lower.indexOf(terminator, from + 5);
          if (found >= 0) end = Math.min(end, found);
        }
        List<String> chunks = splitTopLevel(branch.sqlTail().substring(from + 5, end));
        for (int idx = 1; idx < chunks.size(); idx++) {
          Matcher comma = COMMA_RELATION.matcher(chunks.get(idx));
          if (comma.matches()) names.add(unquoteQualified(comma.group(1)));
        }
      }
    }
    return List.copyOf(names);
  }

  private static List<ScoredOccurrence> scoreCandidates(
      HierarchyPath path,
      List<BoundItem> boundItems,
      List<RelationOccurrence> occurrences,
      RelationOccurrence parent,
      DatabaseStructure structure) {
    Map<RelationOccurrence, Integer> scores = new LinkedHashMap<>();
    for (BoundItem item : boundItems) {
      scores.putIfAbsent(item.occurrence(), 0);
      int score = scores.get(item.occurrence()) + 10;
      DatabaseStructure.CandidateKey key = item.occurrence().relation().preferredKey().orElse(null);
      if (key != null && key.columns().stream().anyMatch(item.column()::equalsIgnoreCase)) score += 50;
      if ("id".equalsIgnoreCase(item.outputTerminal())) score += 25;
      scores.put(item.occurrence(), score);
    }
    if (scores.isEmpty() && occurrences.size() == 1) {
      scores.put(occurrences.getFirst(), 1);
    }
    for (RelationOccurrence occurrence : new ArrayList<>(scores.keySet())) {
      int score = scores.get(occurrence);
      String terminal = DatabaseStructure.normalize(path.getTerminalNodeName());
      if (terminal.equals(DatabaseStructure.normalize(occurrence.alias()))) score += 100;
      if (terminal.equals(DatabaseStructure.normalize(occurrence.relation().name()))) score += 80;
      // The relation directly below the already-selected parent defines the repeated row grain;
      // joined lookup tables merely enrich that object.
      if (parent != null && related(parent, occurrence, structure)) score += 200;
      DatabaseStructure.CandidateKey key = occurrence.relation().preferredKey().orElse(null);
      if (key != null) score += 10 - Math.min(9, key.evidence().rank() * 2);
      scores.put(occurrence, score);
    }
    return scores.entrySet().stream()
        .filter(entry -> entry.getKey().relation().preferredKey().isPresent())
        .map(entry -> new ScoredOccurrence(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparingInt(ScoredOccurrence::score).reversed()
            .thenComparing(item -> item.occurrence().alias(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private static boolean related(
      RelationOccurrence left,
      RelationOccurrence right,
      DatabaseStructure structure) {
    return !relationshipsBetween(left, right, structure).isEmpty();
  }

  private static List<DatabaseStructure.Relationship> relationshipsBetween(
      RelationOccurrence left,
      RelationOccurrence right,
      DatabaseStructure structure) {
    if (left == null || right == null) return List.of();
    return structure.relationships().stream().filter(relationship ->
        relationship.source().equals(left.relation().id()) && relationship.target().equals(right.relation().id())
            || relationship.source().equals(right.relation().id()) && relationship.target().equals(left.relation().id()))
        .toList();
  }

  private static String formatMetadataUsage(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      RelationOccurrence occurrence,
      DatabaseStructure.CandidateKey key,
      RelationOccurrence parent,
      List<DatabaseStructure.Relationship> relationships) {
    StringBuilder result = new StringBuilder("  {")
        .append(dotted(path)).append("}")
        .append(branchLabel(branchIndex, branchCount))
        .append(" -> ").append(occurrence.alias())
        .append(" [").append(occurrence.relation().id().qualifiedName()).append("]")
        .append(", key (").append(String.join(", ", key.columns())).append(")")
        .append(" [").append(key.evidence()).append("]");
    if (parent != null && !relationships.isEmpty()) {
      result.append(", parent ").append(parent.alias()).append(" via ");
      for (int index = 0; index < relationships.size(); index++) {
        if (index > 0) result.append("; ");
        DatabaseStructure.Relationship relationship = relationships.get(index);
        result.append(relationship.source().qualifiedName())
            .append('(').append(String.join(", ", relationship.sourceColumns())).append(") -> ")
            .append(relationship.target().qualifiedName())
            .append('(').append(String.join(", ", relationship.targetColumns())).append(")")
            .append(" [").append(relationship.evidence()).append(']');
      }
    }
    return result.toString();
  }

  private static String formatGroupingUsage(
      HierarchyPath path,
      int branchIndex,
      int branchCount,
      List<String> grouping) {
    return "  {" + dotted(path) + "}" + branchLabel(branchIndex, branchCount)
        + " -> GROUP BY key (" + String.join(", ", grouping) + ")";
  }

  private static String branchLabel(int branchIndex, int branchCount) {
    return branchCount == 1 ? "" : " branch " + (branchIndex + 1);
  }

  private static void logInferenceUsage(List<String> usage) {
    if (usage.isEmpty()) {
      Log.debug("DQL key inference: no inferred relationships used.");
      return;
    }
    Log.debug("Inferred DQL structure relationships used:\n{}", String.join("\n", usage));
  }

  private static List<BoundItem> boundItemsFor(
      HierarchyPath path,
      List<SelectBlueprint.SelectItem> items,
      List<RelationOccurrence> occurrences) {
    List<BoundItem> result = new ArrayList<>();
    for (SelectBlueprint.SelectItem item : items) {
      if (item.outputPath() == null || !path.equals(item.outputPath().parent())) continue;
      Matcher qualified = COLUMN.matcher(item.expression());
      if (qualified.matches()) {
        String alias = unquote(qualified.group(1));
        String column = unquote(qualified.group(2));
        occurrences.stream()
            .filter(occurrence -> occurrence.alias().equalsIgnoreCase(alias))
            .findFirst()
            .ifPresent(occurrence -> result.add(new BoundItem(
                occurrence, column, item.outputPath().getTerminalNodeName())));
        continue;
      }
      Matcher unqualified = UNQUALIFIED_COLUMN.matcher(item.expression());
      if (unqualified.matches()) {
        String column = unquote(unqualified.group(1));
        List<RelationOccurrence> matches = occurrences.stream()
            .filter(occurrence -> occurrence.relation().column(column).isPresent())
            .toList();
        if (matches.size() == 1) {
          result.add(new BoundItem(matches.getFirst(), column, item.outputPath().getTerminalNodeName()));
        }
      }
    }
    return result;
  }

  private static List<RelationOccurrence> relationOccurrences(
      String sqlTail,
      DatabaseStructure structure) {
    if (sqlTail == null) return List.of();
    List<RelationOccurrence> result = new ArrayList<>();
    Matcher matcher = RELATION.matcher(sqlTail);
    while (matcher.find()) {
      String tableToken = matcher.group(1);
      String aliasToken = matcher.group(2);
      if (aliasToken != null && SQL_KEYWORDS.contains(unquote(aliasToken).toLowerCase(Locale.ROOT))) {
        aliasToken = null;
      }
      DatabaseStructure.Relation relation = structure.relation(unquoteQualified(tableToken)).orElse(null);
      if (relation == null) continue;
      String sqlAlias = aliasToken == null ? lastPart(tableToken) : aliasToken;
      result.add(new RelationOccurrence(unquote(sqlAlias), sqlAlias, relation));
    }
    addCommaRelations(sqlTail, structure, result);
    return result;
  }

  private static void addCommaRelations(
      String sqlTail,
      DatabaseStructure structure,
      List<RelationOccurrence> result) {
    String lower = sqlTail.toLowerCase(Locale.ROOT);
    int from = lower.indexOf("from ");
    if (from < 0) return;
    int end = sqlTail.length();
    for (String terminator : List.of(" where ", " group ", " having ", " order ", " limit ", " offset ")) {
      int found = lower.indexOf(terminator, from + 5);
      if (found >= 0) end = Math.min(end, found);
    }
    List<String> chunks = splitTopLevel(sqlTail.substring(from + 5, end));
    for (int idx = 1; idx < chunks.size(); idx++) {
      Matcher comma = COMMA_RELATION.matcher(chunks.get(idx));
      if (!comma.matches()) continue;
      String tableToken = comma.group(1);
      String aliasToken = comma.group(2);
      DatabaseStructure.Relation relation = structure.relation(unquoteQualified(tableToken)).orElse(null);
      if (relation == null) continue;
      String sqlAlias = aliasToken == null ? lastPart(tableToken) : aliasToken;
      String alias = unquote(sqlAlias);
      if (result.stream().noneMatch(existing -> existing.alias().equalsIgnoreCase(alias))) {
        result.add(new RelationOccurrence(alias, sqlAlias, relation));
      }
    }
  }

  private static List<String> groupingExpressions(String sqlTail) {
    if (sqlTail == null) return List.of();
    String lower = sqlTail.toLowerCase(Locale.ROOT);
    int group = lower.indexOf(" group by ");
    if (group < 0) return List.of();
    int start = group + " group by ".length();
    int end = lower.length();
    for (String terminator : List.of(" having ", " limit ", " offset ", " fetch ")) {
      int found = lower.indexOf(terminator, start);
      if (found >= 0) end = Math.min(end, found);
    }
    return splitTopLevel(sqlTail.substring(start, end));
  }

  private static List<String> splitTopLevel(String text) {
    List<String> result = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int idx = 0; idx < text.length(); idx++) {
      char ch = text.charAt(idx);
      if (ch == '(') depth++;
      if (ch == ')') depth--;
      if (ch == ',' && depth == 0) {
        result.add(text.substring(start, idx).trim());
        start = idx + 1;
      }
    }
    String last = text.substring(start).trim();
    if (!last.isEmpty()) result.add(last);
    return result;
  }

  private static boolean isDistinct(SelectBlueprint blueprint) {
    return blueprint.branches().stream().anyMatch(branch -> !branch.items().isEmpty()
        && branch.items().getFirst().expression().stripLeading().toLowerCase(Locale.ROOT).startsWith("distinct "));
  }

  private static boolean isAggregateWithoutGroup(SelectBlueprint blueprint) {
    for (SelectBlueprint.Branch branch : blueprint.branches()) {
      String tail = branch.sqlTail() == null ? "" : branch.sqlTail().toLowerCase(Locale.ROOT);
      if (tail.contains(" group by ")) return false;
      boolean aggregate = branch.items().stream().anyMatch(item -> item.expression().toLowerCase(Locale.ROOT)
          .matches(".*\\b(count|sum|avg|min|max)\\s*\\(.*"));
      if (aggregate) return true;
    }
    return false;
  }

  private static String lastPart(String qualified) {
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? qualified : qualified.substring(dot + 1);
  }

  private static String unquoteQualified(String value) {
    return value.replace("\"", "");
  }

  private static String unquote(String value) {
    if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String dotted(HierarchyPath path) {
    return String.join(".", path.getPathParts());
  }

  private record RelationOccurrence(
      String alias,
      String sqlAlias,
      DatabaseStructure.Relation relation) {
  }

  private record BoundItem(RelationOccurrence occurrence, String column, String outputTerminal) {
  }

  private record ScoredOccurrence(RelationOccurrence occurrence, int score) {
  }
}
