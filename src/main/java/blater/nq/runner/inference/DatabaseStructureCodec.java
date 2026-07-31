package blater.nq.runner.inference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reflection-free persistent representation of a database metadata graph. */
final class DatabaseStructureCodec {
  private static final int MAGIC = 0x4e514b31; // NQK1
  private static final int FORMAT_VERSION = 1;
  private static final int MAX_ITEMS = 1_000_000;
  private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;

  private DatabaseStructureCodec() {
  }

  static byte[] encode(DatabaseStructure structure) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream output = new DataOutputStream(bytes)) {
      output.writeInt(MAGIC);
      output.writeInt(FORMAT_VERSION);
      output.writeLong(structure.inferredAtMillis());
      writeRelations(output, structure.relations());
      writeRelationships(output, structure.relationships());
    }
    return bytes.toByteArray();
  }

  static DatabaseStructure decode(byte[] payload) throws IOException {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      if (input.readInt() != MAGIC) {
        throw new IOException("Unrecognized database-structure cache format");
      }
      int version = input.readInt();
      if (version != FORMAT_VERSION) {
        throw new IOException("Unsupported database-structure cache format version: " + version);
      }
      long inferredAtMillis = input.readLong();
      List<DatabaseStructure.Relation> relations = readRelations(input);
      List<DatabaseStructure.Relationship> relationships = readRelationships(input);
      if (input.read() != -1) {
        throw new IOException("Unexpected trailing database-structure cache data");
      }
      return new DatabaseStructure(relations, relationships, inferredAtMillis);
    } catch (EOFException ex) {
      throw new IOException("Truncated database-structure cache", ex);
    }
  }

  private static void writeRelations(
      DataOutputStream output,
      List<DatabaseStructure.Relation> relations) throws IOException {
    output.writeInt(relations.size());
    for (DatabaseStructure.Relation relation : relations) {
      writeRelationId(output, relation.id());
      writeString(output, relation.type());
      output.writeInt(relation.columns().size());
      for (DatabaseStructure.Column column : relation.columns()) {
        writeString(output, column.name());
        output.writeInt(column.jdbcType());
        writeString(output, column.typeName());
        output.writeBoolean(column.nullable());
        output.writeInt(column.ordinal());
      }
      output.writeInt(relation.candidateKeys().size());
      for (DatabaseStructure.CandidateKey key : relation.candidateKeys()) {
        writeString(output, key.name());
        writeStrings(output, key.columns());
        writeString(output, key.evidence().name());
      }
    }
  }

  private static List<DatabaseStructure.Relation> readRelations(DataInputStream input) throws IOException {
    List<DatabaseStructure.Relation> relations = new ArrayList<>();
    for (int index = 0, count = readCount(input, "relations"); index < count; index++) {
      DatabaseStructure.RelationId id = readRelationId(input);
      String type = readString(input);
      List<DatabaseStructure.Column> columns = new ArrayList<>();
      for (int columnIndex = 0, countColumns = readCount(input, "columns");
           columnIndex < countColumns;
           columnIndex++) {
        columns.add(new DatabaseStructure.Column(
            readString(input), input.readInt(), readString(input), input.readBoolean(), input.readInt()));
      }
      List<DatabaseStructure.CandidateKey> keys = new ArrayList<>();
      for (int keyIndex = 0, countKeys = readCount(input, "candidate keys");
           keyIndex < countKeys;
           keyIndex++) {
        keys.add(new DatabaseStructure.CandidateKey(
            readString(input),
            readStrings(input),
            readEnum(input, DatabaseStructure.KeyEvidence.class, "key evidence")));
      }
      relations.add(new DatabaseStructure.Relation(id, type, columns, keys));
    }
    return relations;
  }

  private static void writeRelationships(
      DataOutputStream output,
      List<DatabaseStructure.Relationship> relationships) throws IOException {
    output.writeInt(relationships.size());
    for (DatabaseStructure.Relationship relationship : relationships) {
      writeString(output, relationship.name());
      writeRelationId(output, relationship.source());
      writeStrings(output, relationship.sourceColumns());
      writeRelationId(output, relationship.target());
      writeStrings(output, relationship.targetColumns());
      writeString(output, relationship.evidence().name());
    }
  }

  private static List<DatabaseStructure.Relationship> readRelationships(
      DataInputStream input) throws IOException {
    List<DatabaseStructure.Relationship> relationships = new ArrayList<>();
    for (int index = 0, count = readCount(input, "relationships"); index < count; index++) {
      relationships.add(new DatabaseStructure.Relationship(
          readString(input),
          readRelationId(input),
          readStrings(input),
          readRelationId(input),
          readStrings(input),
          readEnum(input, DatabaseStructure.RelationshipEvidence.class, "relationship evidence")));
    }
    return relationships;
  }

  private static void writeRelationId(
      DataOutputStream output,
      DatabaseStructure.RelationId id) throws IOException {
    writeString(output, id.catalog());
    writeString(output, id.schema());
    writeString(output, id.name());
  }

  private static DatabaseStructure.RelationId readRelationId(DataInputStream input) throws IOException {
    return new DatabaseStructure.RelationId(readString(input), readString(input), readString(input));
  }

  private static void writeStrings(DataOutputStream output, List<String> values) throws IOException {
    output.writeInt(values.size());
    for (String value : values) {
      writeString(output, value);
    }
  }

  private static List<String> readStrings(DataInputStream input) throws IOException {
    List<String> values = new ArrayList<>();
    for (int index = 0, count = readCount(input, "strings"); index < count; index++) {
      values.add(readString(input));
    }
    return values;
  }

  private static void writeString(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static String readString(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length == -1) return null;
    if (length < 0 || length > MAX_STRING_BYTES) {
      throw new IOException("Invalid database-structure string length: " + length);
    }
    byte[] encoded = new byte[length];
    input.readFully(encoded);
    return new String(encoded, StandardCharsets.UTF_8);
  }

  private static int readCount(DataInputStream input, String description) throws IOException {
    int count = input.readInt();
    if (count < 0 || count > MAX_ITEMS) {
      throw new IOException("Invalid number of " + description + ": " + count);
    }
    return count;
  }

  private static <E extends Enum<E>> E readEnum(
      DataInputStream input,
      Class<E> type,
      String description) throws IOException {
    String value = readString(input);
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException | NullPointerException ex) {
      throw new IOException("Invalid " + description + ": " + value, ex);
    }
  }
}
