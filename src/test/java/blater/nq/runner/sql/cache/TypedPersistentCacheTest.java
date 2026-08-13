package blater.nq.runner.sql.cache;

import blater.nq.cli.CacheName;
import blater.nq.cli.CacheNameSelection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedPersistentCacheTest {
  @TempDir
  Path tempDirectory;

  @Test
  void readOnlyOperationsDoNotCreateAnAbsentDirectCacheDirectory() {
    Path cacheDirectory = tempDirectory.resolve("absent-cache");

    assertInstanceOf(CacheLookup.None.class, PersistentCache.active(cacheDirectory));
    assertEquals(java.util.List.of(), PersistentCache.listCaches(cacheDirectory));
    assertEquals(0, PersistentCache.clearNamed(new CacheName("customers"), cacheDirectory));
    assertEquals(0, PersistentCache.clearOlderThan(Duration.ofDays(1), cacheDirectory));
    assertEquals(0, PersistentCache.clearAll(cacheDirectory));

    assertFalse(Files.exists(cacheDirectory));
  }

  @Test
  void namedLoadUsesDirectLayoutAndActivatesOnlyAfterSuccess() throws Exception {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName customers = new CacheName("Customers");

    CacheHandle handle = PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Named(customers),
        this::createDatabase);

    assertEquals(cacheDirectory.resolve("customers.mv.db").toAbsolutePath(), handle.cacheFile());
    assertTrue(Files.isRegularFile(handle.cacheFile()));
    assertEquals("customers\n", Files.readString(cacheDirectory.resolve(".active")));
    CacheLookup.Found active = assertInstanceOf(
        CacheLookup.Found.class, PersistentCache.active(cacheDirectory));
    assertEquals(customers, active.name());
    assertEquals(handle.cacheFile(), active.handle().cacheFile());
    assertFalse(Files.isDirectory(cacheDirectory.resolve("cache")));
  }

  @Test
  void namedPreparationFailsIfAnyDatabaseArtifactExists() throws Exception {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName name = new CacheName("customers");
    PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(name), this::createDatabase);

    IllegalArgumentException failure = assertThrows(
        IllegalArgumentException.class,
        () -> PersistentCache.prepare(cacheDirectory, new CacheNameSelection.Named(name)));

    assertTrue(failure.getMessage().contains("already exists"));
  }

  @Test
  void preparationClaimsNamedCacheAcrossConcurrentCallers() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName name = new CacheName("customers");

    try (CachePreparation first = PersistentCache.prepare(
        cacheDirectory, new CacheNameSelection.Named(name))) {
      IllegalArgumentException failure = assertThrows(
          IllegalArgumentException.class,
          () -> PersistentCache.prepare(cacheDirectory, new CacheNameSelection.Named(name)));
      assertTrue(failure.getMessage().contains("already being created"));
      assertFalse(Files.exists(first.handle().cacheFile()));
    }

    try (CachePreparation availableAgain = PersistentCache.prepare(
        cacheDirectory, new CacheNameSelection.Named(name))) {
      assertEquals(
          cacheDirectory.resolve("customers.mv.db").toAbsolutePath(),
          availableAgain.handle().cacheFile());
    }
  }

  @Test
  void generatedLoadCreatesAPortableLogicalName() {
    Path cacheDirectory = tempDirectory.resolve("cache");

    CacheHandle handle = PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Generated(),
        this::createDatabase);

    String filename = handle.cacheFile().getFileName().toString();
    CacheName generated = new CacheName(
        filename.substring(0, filename.length() - ".mv.db".length()));
    CacheLookup.Found active = assertInstanceOf(
        CacheLookup.Found.class, PersistentCache.active(cacheDirectory));
    assertEquals(generated, active.name());
  }

  @Test
  void namedSelectionDoesNotActivateButUseDoes() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName first = new CacheName("first");
    CacheName second = new CacheName("second");
    CacheHandle firstHandle = PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(first), this::createDatabase);
    CacheHandle secondHandle = PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(second), this::createDatabase);

    CacheHandle selected = PersistentCache.select(first, cacheDirectory);

    assertEquals(firstHandle.cacheFile(), selected.cacheFile());
    assertEquals(second, active(cacheDirectory).name());

    CacheHandle used = PersistentCache.use(first, cacheDirectory);

    assertEquals(firstHandle.cacheFile(), used.cacheFile());
    assertEquals(first, active(cacheDirectory).name());
    assertFalse(secondHandle.cacheFile().equals(used.cacheFile()));
  }

  @Test
  void listReturnsLogicalNamesAndActiveStatusWithoutOpeningDatabases() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Named(new CacheName("beta")),
        this::createDatabase);
    PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Named(new CacheName("alpha")),
        this::createDatabase);
    try {
      Files.writeString(cacheDirectory.resolve("not.a.valid.cache.mv.db"), "ignored");
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }

    var entries = PersistentCache.listCaches(cacheDirectory);

    assertEquals(2, entries.size());
    assertEquals(new CacheName("alpha"), entries.get(0).name());
    assertTrue(entries.get(0).active());
    assertEquals(new CacheName("beta"), entries.get(1).name());
    assertFalse(entries.get(1).active());
    assertTrue(entries.stream().allMatch(entry -> entry.modifiedMillis() > 0));
  }

  @Test
  void clearNamedIsIdempotentAndClearingActiveRemovesSelection() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName name = new CacheName("customers");
    CacheHandle handle = PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(name), this::createDatabase);

    assertEquals(1, PersistentCache.clearNamed(name, cacheDirectory));
    assertFalse(Files.exists(handle.cacheFile()));
    assertFalse(Files.exists(cacheDirectory.resolve(".active")));
    assertInstanceOf(CacheLookup.None.class, PersistentCache.active(cacheDirectory));
    assertEquals(0, PersistentCache.clearNamed(name, cacheDirectory));
  }

  @Test
  void clearOlderThanClearsOnlyOldCachesAndTheirSelection() throws Exception {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName old = new CacheName("old-cache");
    CacheName recent = new CacheName("recent-cache");
    CacheHandle oldHandle = PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(old), this::createDatabase);
    CacheHandle recentHandle = PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(recent), this::createDatabase);
    Files.setLastModifiedTime(oldHandle.cacheFile(), FileTime.fromMillis(1));

    assertEquals(1, PersistentCache.clearOlderThan(Duration.ofDays(1), cacheDirectory));

    assertFalse(Files.exists(oldHandle.cacheFile()));
    assertTrue(Files.exists(recentHandle.cacheFile()));
    assertEquals(recent, active(cacheDirectory).name());
  }

  @Test
  void clearAllIsIdempotentAndRemovesActiveSelection() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Named(new CacheName("first")),
        this::createDatabase);
    PersistentCache.loadAndActivate(
        cacheDirectory,
        new CacheNameSelection.Named(new CacheName("second")),
        this::createDatabase);

    assertEquals(2, PersistentCache.clearAll(cacheDirectory));
    assertEquals(0, PersistentCache.clearAll(cacheDirectory));
    assertEquals(java.util.List.of(), PersistentCache.listCaches(cacheDirectory));
    assertFalse(Files.exists(cacheDirectory.resolve(".active")));
  }

  @Test
  void failedStrictLoadDeletesPartialArtifactsAndPreservesPreviousActiveCache() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName stable = new CacheName("stable");
    PersistentCache.loadAndActivate(
        cacheDirectory, new CacheNameSelection.Named(stable), this::createDatabase);
    CacheName broken = new CacheName("broken");

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> PersistentCache.loadAndActivate(
            cacheDirectory,
            new CacheNameSelection.Named(broken),
            handle -> {
              createDatabase(handle);
              throw new IllegalStateException("invalid input row");
            }));

    assertEquals("invalid input row", failure.getMessage());
    assertFalse(Files.exists(cacheDirectory.resolve("broken.mv.db")));
    assertEquals(stable, active(cacheDirectory).name());
    assertEquals("stable\n", readActive(cacheDirectory));
  }

  @Test
  void missingDatabaseAfterLoadIsFailureAndIsNotActivated() {
    Path cacheDirectory = tempDirectory.resolve("cache");
    CacheName missing = new CacheName("missing");

    IllegalStateException failure = assertThrows(
        IllegalStateException.class,
        () -> PersistentCache.loadAndActivate(
            cacheDirectory,
            new CacheNameSelection.Named(missing),
            ignored -> { }));

    assertTrue(failure.getMessage().contains("without creating"));
    assertInstanceOf(CacheLookup.None.class, PersistentCache.active(cacheDirectory));
    assertFalse(Files.exists(cacheDirectory.resolve("missing.mv.db")));
  }

  private CacheLookup.Found active(Path cacheDirectory) {
    return assertInstanceOf(CacheLookup.Found.class, PersistentCache.active(cacheDirectory));
  }

  private String readActive(Path cacheDirectory) {
    try {
      return Files.readString(cacheDirectory.resolve(".active"));
    } catch (java.io.IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private void createDatabase(CacheHandle handle) {
    try (var ignored = DriverManager.getConnection(handle.jdbcUrl(), "sa", "")) {
      // Closing the connection completes H2's on-disk cache file.
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
