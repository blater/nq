package blater.nq.runner.inference;

import blater.nq.runner.inference.DatabaseStructure;
import blater.nq.runner.inference.DatabaseStructureCodec;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.util.List;

import static blater.nq.runner.inference.DatabaseStructure.KeyEvidence.PRIMARY_KEY;
import static blater.nq.runner.inference.DatabaseStructure.RelationshipEvidence.DECLARED_FOREIGN_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseStructureCodecTest {
  @Test
  void roundTripsDatabaseMetadataWithoutJavaSerialization() throws Exception {
    DatabaseStructure.RelationId customer =
        new DatabaseStructure.RelationId(null, "PUBLIC", "CUSTOMER");
    DatabaseStructure.RelationId purchase =
        new DatabaseStructure.RelationId(null, "PUBLIC", "PURCHASE");
    DatabaseStructure structure = new DatabaseStructure(
        List.of(
            new DatabaseStructure.Relation(
                customer,
                "BASE TABLE",
                List.of(new DatabaseStructure.Column("ID", Types.INTEGER, "INTEGER", false, 1)),
                List.of(new DatabaseStructure.CandidateKey("PK_CUSTOMER", List.of("ID"), PRIMARY_KEY))),
            new DatabaseStructure.Relation(
                purchase,
                "BASE TABLE",
                List.of(new DatabaseStructure.Column("CUSTOMER_ID", Types.INTEGER, "INTEGER", true, 1)),
                List.of())),
        List.of(new DatabaseStructure.Relationship(
            "FK_PURCHASE_CUSTOMER",
            purchase,
            List.of("CUSTOMER_ID"),
            customer,
            List.of("ID"),
            DECLARED_FOREIGN_KEY)),
        123456789L);

    assertEquals(structure, DatabaseStructureCodec.decode(DatabaseStructureCodec.encode(structure)));
  }
}
