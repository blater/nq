package blater.nq.runner.inference;

import blater.nq.ParameterParser;
import blater.nq.runner.sql.cache.PersistentCache;
import blater.nq.testsupport.H2Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static blater.nq.runner.inference.DatabaseStructure.KeyEvidence.LOGICAL_LINK_KEY;
import static blater.nq.runner.inference.DatabaseStructure.KeyEvidence.PRIMARY_KEY;
import static blater.nq.runner.inference.DatabaseStructure.KeyEvidence.UNIQUE_INDEX;
import static blater.nq.runner.inference.DatabaseStructure.RelationshipEvidence.DECLARED_FOREIGN_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DatabaseStructureInferrerTest {
  @Test
  void targetIdentityAndCacheNameRequireOnlyConfiguration(@TempDir Path tempDir) {
    Map<String, String> parameters = Map.of(
        ParameterParser.JDBC_DRIVER_PARAM, "sqlserver",
        ParameterParser.JDBC_DATABASE_PARAM,
        "jdbc:sqlserver://unreachable.invalid;user=report;password=secret;databaseName=work",
        ParameterParser.JDBC_USERNAME_PARAM, "report",
        ParameterParser.CACHE_DIR_PARAM, tempDir.toString());

    DatabaseTargetIdentity identity = DatabaseTargetIdentity.from(parameters);
    Path cacheFile = PersistentCache.cacheFile(identity.identityText(), parameters);

    assertTrue(identity.identityText().contains("user=report"));
    assertFalse(identity.identityText().contains("secret"));
    assertEquals(tempDir, cacheFile.getParent());
    assertTrue(cacheFile.getFileName().toString().matches("cache-[0-9a-f]{64}\\.mv\\.db"));
    assertEquals(
        "jdbc:sqlserver://db;user=report;password=<redacted>;databaseName=work",
        DatabaseTargetIdentity.sanitizeUrl(
            "jdbc:sqlserver://db;user=report;password=secret;databaseName=work"));
  }

  @Test
  void discoversDeclaredCompositeAndLogicalKeysAndRelationships() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table person (id integer primary key, name varchar(80))",
          "create table project (tenant_id integer not null, project_id integer not null, name varchar(80), "
              + "primary key (tenant_id, project_id))",
          "create table membership (person_id integer not null, tenant_id integer not null, project_id integer not null, "
              + "constraint uq_membership unique(person_id, tenant_id, project_id), "
              + "foreign key(person_id) references person(id), "
              + "foreign key(tenant_id, project_id) references project(tenant_id, project_id))",
          "create table assignment (person_id integer not null, tenant_id integer not null, project_id integer not null, "
              + "foreign key(person_id) references person(id), "
              + "foreign key(tenant_id, project_id) references project(tenant_id, project_id))");

      DatabaseStructure structure = DatabaseStructureInferrer.infer(database.connection());

      DatabaseStructure.Relation project = structure.relation("project").orElseThrow();
      assertEquals(PRIMARY_KEY, project.preferredKey().orElseThrow().evidence());
      assertEquals(List.of("TENANT_ID", "PROJECT_ID"), project.preferredKey().orElseThrow().columns());

      DatabaseStructure.Relation membership = structure.relation("membership").orElseThrow();
      assertTrue(membership.candidateKeys().stream().anyMatch(key ->
          key.evidence() == UNIQUE_INDEX
              && key.columns().equals(List.of("PERSON_ID", "TENANT_ID", "PROJECT_ID"))));
      DatabaseStructure.Relation assignment = structure.relation("assignment").orElseThrow();
      assertTrue(assignment.candidateKeys().stream().anyMatch(key -> key.evidence() == LOGICAL_LINK_KEY));
      assertEquals(2, structure.relationships().stream()
          .filter(relationship -> relationship.source().equals(membership.id()))
          .filter(relationship -> relationship.evidence() == DECLARED_FOREIGN_KEY)
          .count());
    }
  }

  @Test
  void infersConventionalKeyAndTypeCompatibleRelationshipWithoutReadingRows() throws Exception {
    try (H2Database database = new H2Database()) {
      database.execute(
          "create table users (id integer, name varchar(80))",
          "create table addresses (address_id integer, user_id integer, line varchar(80))");

      DatabaseStructure structure = DatabaseStructureInferrer.infer(database.connection());

      DatabaseStructure.Relation users = structure.relation("users").orElseThrow();
      assertEquals(List.of("ID"), users.preferredKey().orElseThrow().columns());
      assertTrue(structure.relationships().stream().anyMatch(relationship ->
          relationship.source().name().equalsIgnoreCase("addresses")
              && relationship.target().name().equalsIgnoreCase("users")
              && relationship.sourceColumns().equals(List.of("USER_ID"))));
    }
  }
}
