package blater.nq.runner.sql.cache;

import blater.nq.inputreader.RelationPathExpression;
import blater.nq.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static blater.nq.ParameterParser.ANONYMOUS_COLLECTIONS_PARAM;
import static blater.nq.ParameterParser.RELATION_ALIAS_PREFIX;

public record MaterializationConfiguration(
    AnonymousCollectionMode anonymousMode,
    List<RelationAlias> aliases,
    boolean explicitlyConfigured) {
  public static final int LAYOUT_VERSION = 2;

  public MaterializationConfiguration {
    anonymousMode = anonymousMode == null ? AnonymousCollectionMode.MERGE : anonymousMode;
    aliases = List.copyOf(aliases == null ? List.of() : aliases);
  }

  public static MaterializationConfiguration from(Map<String, String> parameters) {
    String rawMode = parameters == null ? null : parameters.get(ANONYMOUS_COLLECTIONS_PARAM);
    AnonymousCollectionMode mode = AnonymousCollectionMode.parse(rawMode);
    Map<String, RelationAlias> aliases = new LinkedHashMap<>();
    if (parameters != null) {
      parameters.entrySet().stream()
          .filter(entry -> entry.getKey().startsWith(RELATION_ALIAS_PREFIX))
          .sorted(Map.Entry.comparingByKey())
          .forEach(entry -> addAlias(aliases, entry.getValue()));
    }
    List<RelationAlias> sorted = aliases.values().stream()
        .sorted(Comparator.comparing(alias -> alias.source().canonical()))
        .toList();
    return new MaterializationConfiguration(
        mode,
        sorted,
        rawMode != null || !sorted.isEmpty());
  }

  public String canonicalKey() {
    StringBuilder key = new StringBuilder()
        .append("layoutVersion=").append(LAYOUT_VERSION).append('\n')
        .append("anonymousCollections=").append(anonymousMode.name().toLowerCase(Locale.ROOT)).append('\n');
    for (RelationAlias alias : aliases) {
      key.append("alias=").append(alias.source().canonical())
          .append('=').append(alias.sqlIdentity()).append('\n');
    }
    return key.toString();
  }

  public boolean isDefault() {
    return anonymousMode == AnonymousCollectionMode.MERGE && aliases.isEmpty();
  }

  public String variantId() {
    if (isDefault()) return "default-v" + LAYOUT_VERSION;
    return "config-" + sha256(canonicalKey()).substring(0, 12);
  }

  private static void addAlias(Map<String, RelationAlias> aliases, String raw) {
    int split = raw == null ? -1 : raw.lastIndexOf('=');
    if (split <= 0 || split == raw.length() - 1) {
      Log.fatal(IllegalArgumentException.class,
          "--relation-alias requires <source-path>=<relation-name>.");
    }
    RelationPathExpression source;
    try {
      source = new RelationPathExpression(raw.substring(0, split));
    } catch (IllegalArgumentException ex) {
      Log.fatal(IllegalArgumentException.class,
          "Invalid --relation-alias path: " + ex.getMessage());
      return;
    }
    String target = raw.substring(split + 1);
    if (!target.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      Log.fatal(IllegalArgumentException.class,
          "Relation alias name must match [A-Za-z_][A-Za-z0-9_]*: " + target);
    }
    RelationAlias alias = new RelationAlias(source, target, CacheIdentifierNaming.sqlIdentity(target));
    RelationAlias previous = aliases.putIfAbsent(source.canonical(), alias);
    if (previous != null && !previous.sqlIdentity().equals(alias.sqlIdentity())) {
      Log.fatal(IllegalArgumentException.class,
          "Conflicting aliases for relation path [" + source + "].");
    }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }

  public enum AnonymousCollectionMode {
    MERGE,
    ERROR;

    static AnonymousCollectionMode parse(String value) {
      if (value == null || value.isBlank()) return MERGE;
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "merge" -> MERGE;
        case "error" -> ERROR;
        default -> Log.fatal(IllegalArgumentException.class,
            "--anonymous-collections must be one of: merge, error");
      };
    }
  }

  public record RelationAlias(
      RelationPathExpression source,
      String logicalName,
      String sqlIdentity) { }
}
