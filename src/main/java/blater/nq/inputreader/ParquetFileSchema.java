package blater.nq.inputreader;

import blater.nq.execution.EngineParameterNames;
import blater.nq.inputreader.ParquetInputReader.ParquetNames;
import blater.nq.util.Log;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.ParquetRuntimeException;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blater.nq.inputreader.ParquetSchemaShape.isMap;
import static blater.nq.inputreader.ParquetSchemaShape.isScalarStruct;
import static blater.nq.inputreader.ParquetSchemaShape.publicName;
import static blater.nq.util.ValueUtil.hasValue;

/** Reads and validates the schema metadata needed before Parquet records are emitted. */
final class ParquetFileSchema {
  private ParquetFileSchema() {
  }

  static PublicNames publicNames(Path path, MessageType schema, Map<String, String> parameters) {
    return new PublicNames(
        ParquetNames.project(rawRootName(path, parameters), "parquet root"),
        ParquetNames.project(rawRecordName(schema, parameters), "parquet record"));
  }

  private static String rawRootName(Path path, Map<String, String> parameters) {
    if (hasParameter(parameters, EngineParameterNames.PARQUET_ROOT)) {
      return parameters.get(EngineParameterNames.PARQUET_ROOT);
    }
    Path fileName = path.getFileName();
    if (fileName == null) {
      return Log.fatal(IllegalArgumentException.class, "Parquet input file name is empty.");
    }
    String name = fileName.toString();
    if (name.toLowerCase().endsWith(".parquet")) {
      name = name.substring(0, name.length() - ".parquet".length());
    }
    if (name.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "Parquet input file stem is empty.");
    }
    return name;
  }

  private static String rawRecordName(MessageType schema, Map<String, String> parameters) {
    if (hasParameter(parameters, EngineParameterNames.PARQUET_RECORD)) {
      return parameters.get(EngineParameterNames.PARQUET_RECORD);
    }
    return schema == null || !hasValue(schema.getName()) ? "" : schema.getName();
  }

  private static boolean hasParameter(Map<String, String> parameters, String key) {
    return parameters != null && parameters.containsKey(key);
  }

  static MessageType readSchema(Path path, String filename) {
    ParquetReadOptions options = ParquetReadOptions.builder(new PlainParquetConfiguration()).build();
    try (ParquetFileReader reader = new ParquetFileReader(new LocalInputFile(path), options)) {
      return reader.getFileMetaData().getSchema();
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (ParquetRuntimeException | IllegalArgumentException e) {
      return Log.fatal(IllegalStateException.class, "Malformed Parquet input file: " + filename, e);
    }
  }

  static void validateSchema(GroupType groupType, String path) {
    List<ParquetNames.OriginalName> names = new ArrayList<>();
    for (Type field : groupType.getFields()) {
      names.add(new ParquetNames.OriginalName(field.getName(), path + "." + field.getName()));
    }
    ParquetNames.validateProjectedSiblingNames(names, path);
    for (Type field : groupType.getFields()) {
      if (!field.isPrimitive()) {
        validateSchema(field.asGroupType(), path + "." + field.getName());
      }
    }
    reservedOutputs(groupType, path);
  }

  static Map<String, String> reservedOutputs(GroupType groupType, String path) {
    Map<String, String> outputs = new LinkedHashMap<>();
    for (int index = 0; index < groupType.getFieldCount(); index++) {
      Type field = groupType.getType(index);
      if (isMap(field)) {
        continue;
      }
      if (isScalarStruct(field)) {
        reserveScalarStruct(outputs, field.asGroupType(), field, path);
      } else {
        reserve(outputs, publicName(field, path + "." + field.getName()), path + "." + field.getName());
      }
    }
    return outputs;
  }

  private static void reserveScalarStruct(
      Map<String, String> outputs,
      GroupType struct,
      Type field,
      String path) {

    String fieldPath = path + "." + field.getName();
    String structName = publicName(field, fieldPath);
    for (Type child : struct.getFields()) {
      String childPath = fieldPath + "." + child.getName();
      reserve(outputs, structName + "_" + publicName(child, childPath), childPath);
    }
  }

  private static void reserve(Map<String, String> outputs, String publicName, String sourcePath) {
    String previous = outputs.putIfAbsent(publicName, sourcePath);
    if (previous != null) {
      Log.fatal(
          IllegalArgumentException.class,
          "Parquet projected output name collision for ["
              + publicName + "] between [" + previous + "] and [" + sourcePath + "]");
    }
  }

  record PublicNames(String root, String record) {
  }
}
