package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import org.apache.parquet.ParquetRuntimeException;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.MessageType;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

import static blater.nq.inputreader.ParquetFileSchema.publicNames;
import static blater.nq.inputreader.ParquetFileSchema.readSchema;
import static blater.nq.inputreader.ParquetFileSchema.validateSchema;
import static blater.nq.util.ValueUtil.hasValue;

/** Owns Parquet file lifecycle while the reader focuses on record emission. */
final class ParquetHierarchyLoader {
  private ParquetHierarchyLoader() {
  }

  static Hierarchy load(String filename, Map<String, String> parameters) {
    if (!hasValue(filename)) {
      return new Hierarchy();
    }
    Path path = Path.of(filename);
    if (isEmpty(path, filename)) {
      return new Hierarchy();
    }
    MessageType schema = readSchema(path, filename);
    ParquetFileSchema.PublicNames names = publicNames(path, schema, parameters);
    validateSchema(schema, schema.getName());
    Node root = new Node(names.root());
    readRecords(root, path, filename, schema, names.record(), parameters);
    return new Hierarchy(root);
  }

  private static boolean isEmpty(Path path, String filename) {
    try {
      return Files.size(path) == 0;
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    }
  }

  private static void readRecords(
      Node root,
      Path path,
      String filename,
      MessageType schema,
      String recordName,
      Map<String, String> parameters) {

    try (ParquetReader<Group> reader = new GroupReaderBuilder(new LocalInputFile(path)).build()) {
      Group record;
      while ((record = reader.read()) != null) {
        root.addNode(ParquetInputReader.emitRecord(record, schema, recordName, parameters));
      }
    } catch (IOException e) {
      Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (ParquetRuntimeException e) {
      Log.fatal(IllegalStateException.class, "Malformed Parquet input file: " + filename, e);
    }
  }

  private static final class GroupReaderBuilder extends ParquetReader.Builder<Group> {
    private GroupReaderBuilder(InputFile file) throws IOException {
      super(file, new PlainParquetConfiguration());
    }

    @Override
    protected ReadSupport<Group> getReadSupport() {
      return new GroupReadSupport();
    }
  }
}
