package blater.nq.testsupport;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;

import java.nio.file.Path;

public final class ParquetTestFiles {
  private ParquetTestFiles() {
  }

  public static MessageType schema(String schema) {
    return MessageTypeParser.parseMessageType(schema);
  }

  public static SimpleGroupFactory factory(MessageType schema) {
    return new SimpleGroupFactory(schema);
  }

  public static void write(Path path, MessageType schema, Group... records) throws Exception {
    try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(path))
        .withConf(new PlainParquetConfiguration())
        .withType(schema)
        .withCompressionCodec(CompressionCodecName.UNCOMPRESSED)
        .build()) {
      for (Group record : records) {
        writer.write(record);
      }
    }
  }
}
