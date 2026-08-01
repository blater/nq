package blater.nq.inputreader;

import blater.nq.ParameterParser;
import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;
import org.apache.parquet.ParquetReadOptions;
import org.apache.parquet.ParquetRuntimeException;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.GroupValueSource;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.api.ReadSupport;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;

import static blater.nq.util.ValueUtil.hasValue;
import static org.apache.parquet.schema.Type.Repetition.REPEATED;

public class ParquetInputReader implements InputReader {
  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    if (!hasValue(filename)) {
      return new Hierarchy();
    }

    Path path = Path.of(filename);
    try {
      if (Files.size(path) == 0) {
        return new Hierarchy();
      }
    } catch (NoSuchFileException | AccessDeniedException e) {
      return Log.fatal(IllegalStateException.class, "Could not open input file: " + filename, e);
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    }

    MessageType schema = readSchema(path, filename);
    PublicNames publicNames = publicNames(path, schema, parameters);
    validateSchema(schema, schema.getName());

    Node root = new Node(publicNames.root());
    try (ParquetReader<Group> reader = new GroupReaderBuilder(new LocalInputFile(path)).build()) {
      Group record;
      while ((record = reader.read()) != null) {
        Node recordNode = new Node(publicNames.record());
        recordNode.setArrayItem(true);
        RecordContext context = new RecordContext(
            publicNames.record(),
            null,
            reservedOutputs(schema, schema.getName()),
            parameters);
        emitGroupFields(recordNode, record, schema, context, schema.getName());
        root.addNode(recordNode);
      }
    } catch (IOException e) {
      return Log.fatal(IllegalStateException.class, "Could not read input file: " + filename, e);
    } catch (ParquetRuntimeException e) {
      return Log.fatal(IllegalStateException.class, "Malformed Parquet input file: " + filename, e);
    }

    return new Hierarchy(root);
  }

  private static PublicNames publicNames(Path path, MessageType schema, Map<String, String> parameters) {
    return new PublicNames(
        ParquetNames.project(rawRootName(path, parameters), "parquet root"),
        ParquetNames.project(rawRecordName(schema, parameters), "parquet record"));
  }

  private static String rawRootName(Path path, Map<String, String> parameters) {
    if (hasParameter(parameters, ParameterParser.PARQUET_ROOT_PARAM)) {
      return parameters.get(ParameterParser.PARQUET_ROOT_PARAM);
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
    if (hasParameter(parameters, ParameterParser.PARQUET_RECORD_PARAM)) {
      return parameters.get(ParameterParser.PARQUET_RECORD_PARAM);
    }
    if (schema == null || !hasValue(schema.getName())) {
      return "";
    }
    return schema.getName();
  }

  private static boolean hasParameter(Map<String, String> parameters, String key) {
    return parameters != null && parameters.containsKey(key);
  }

  private static MessageType readSchema(Path path, String filename) {
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

  private static void validateSchema(GroupType groupType, String path) {
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

  private static Map<String, String> reservedOutputs(GroupType groupType, String path) {
    Map<String, String> outputs = new LinkedHashMap<>();
    for (int index = 0; index < groupType.getFieldCount(); index++) {
      Type field = groupType.getType(index);
      if (isMap(field)) {
        continue;
      }
      if (isScalarStruct(field)) {
        String structName = publicName(field, path + "." + field.getName());
        GroupType struct = field.asGroupType();
        for (Type child : struct.getFields()) {
          String childName = publicName(child, path + "." + field.getName() + "." + child.getName());
          reserve(outputs, structName + "_" + childName, path + "." + field.getName() + "." + child.getName());
        }
      } else {
        reserve(outputs, publicName(field, path + "." + field.getName()), path + "." + field.getName());
      }
    }
    return outputs;
  }

  private static void reserve(Map<String, String> outputs, String publicName, String sourcePath) {
    String previous = outputs.putIfAbsent(publicName, sourcePath);
    if (previous != null) {
      Log.fatal(
          IllegalArgumentException.class,
          "Parquet projected output name collision for ["
              + publicName
              + "] between ["
              + previous
              + "] and ["
              + sourcePath
              + "]");
    }
  }

  private static void emitGroupFields(
      Node parent,
      GroupValueSource source,
      GroupType groupType,
      RecordContext context,
      String parquetPath) {

    for (int index = 0; index < groupType.getFieldCount(); index++) {
      Type field = groupType.getType(index);
      String fieldPath = parquetPath + "." + field.getName();
      String publicName = publicName(field, fieldPath);

      if (isMap(field)) {
        emitMap(parent, source, index, field.asGroupType(), context, fieldPath);
      } else if (isList(field)) {
        emitList(parent, source, index, field.asGroupType(), publicName, context, fieldPath, OutputOrigin.DIRECT);
      } else if (field.isPrimitive()) {
        emitPrimitiveField(
            parent,
            source,
            index,
            field.asPrimitiveType(),
            publicName,
            context,
            fieldPath,
            OutputOrigin.DIRECT,
            field.isRepetition(REPEATED));
      } else if (isScalarStruct(field)) {
        emitScalarStruct(parent, source, index, field.asGroupType(), publicName, context, fieldPath);
      } else {
        emitRecordField(parent, source, index, field.asGroupType(), publicName, context, fieldPath);
      }
    }
  }

  private static void emitPrimitiveField(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      PrimitiveType type,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin,
      boolean arrayItem) {

    int count = source.getFieldRepetitionCount(fieldIndex);
    if (count == 0) {
      if (!type.isRepetition(REPEATED)) {
        addNullNode(parent, publicName, context, fieldPath, origin, false);
      }
      return;
    }

    for (int valueIndex = 0; valueIndex < count; valueIndex++) {
      ScalarValue value = scalarValue(source, fieldIndex, valueIndex, type, fieldPath);
      Node child = new Node(publicName);
      child.setValue(value.templateString()
          ? Template.expand(value.value(), context.parameters())
          : value.value());
      child.setArrayItem(arrayItem);
      context.register(publicName, fieldPath, origin, arrayItem);
      parent.addNode(child);
    }
  }

  private static void emitScalarStruct(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      GroupType structType,
      String structName,
      RecordContext context,
      String fieldPath) {

    int count = source.getFieldRepetitionCount(fieldIndex);
    if (count == 0) {
      return;
    }
    if (count > 1) {
      Log.fatal(IllegalArgumentException.class, "Malformed Parquet scalar struct repeats at " + fieldPath);
    }

    GroupValueSource struct = source.getGroup(fieldIndex, 0);
    for (int childIndex = 0; childIndex < structType.getFieldCount(); childIndex++) {
      Type childType = structType.getType(childIndex);
      String childPath = fieldPath + "." + childType.getName();
      String childName = publicName(childType, childPath);
      emitPrimitiveField(
          parent,
          struct,
          childIndex,
          childType.asPrimitiveType(),
          structName + "_" + childName,
          context,
          childPath,
          OutputOrigin.STRUCT_FLATTENED,
          false);
    }
  }

  private static void emitRecordField(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      GroupType recordType,
      String publicName,
      RecordContext context,
      String fieldPath) {

    int count = source.getFieldRepetitionCount(fieldIndex);
    for (int valueIndex = 0; valueIndex < count; valueIndex++) {
      Node child = new Node(publicName);
      child.setArrayItem(recordType.isRepetition(REPEATED));
      RecordContext childContext = new RecordContext(
          publicName,
          context.recordName(),
          reservedOutputs(recordType, fieldPath),
          context.parameters());
      emitGroupFields(child, source.getGroup(fieldIndex, valueIndex), recordType, childContext, fieldPath);
      context.register(publicName, fieldPath, OutputOrigin.DIRECT, recordType.isRepetition(REPEATED));
      parent.addNode(child);
    }
  }

  private static void emitList(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      GroupType listType,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin) {

    ListShape shape = listShape(listType, fieldPath);
    int wrapperCount = source.getFieldRepetitionCount(fieldIndex);
    for (int wrapperIndex = 0; wrapperIndex < wrapperCount; wrapperIndex++) {
      GroupValueSource wrapper = source.getGroup(fieldIndex, wrapperIndex);
      int itemCount = wrapper.getFieldRepetitionCount(shape.repeatedIndex());
      for (int itemIndex = 0; itemIndex < itemCount; itemIndex++) {
        emitListItem(parent, wrapper, itemIndex, shape, publicName, context, fieldPath, origin);
      }
    }
  }

  private static void emitListItem(
      Node parent,
      GroupValueSource wrapper,
      int itemIndex,
      ListShape shape,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin) {

    Type repeatedType = shape.repeatedType();
    if (repeatedType.isPrimitive()) {
      emitPrimitiveOccurrence(
          parent,
          wrapper,
          shape.repeatedIndex(),
          itemIndex,
          repeatedType.asPrimitiveType(),
          publicName,
          context,
          fieldPath + "." + repeatedType.getName(),
          origin,
          true);
      return;
    }

    GroupValueSource itemGroup = wrapper.getGroup(shape.repeatedIndex(), itemIndex);
    if (shape.unwrapElement()) {
      int elementCount = itemGroup.getFieldRepetitionCount(shape.elementIndex());
      if (elementCount == 0) {
        addNullNode(parent, publicName, context, fieldPath + "." + shape.elementType().getName(), origin, true);
        return;
      }
      emitElementValue(
          parent,
          itemGroup,
          shape.elementIndex(),
          0,
          shape.elementType(),
          publicName,
          context,
          fieldPath + "." + shape.elementType().getName(),
          origin,
          true);
      return;
    }

    emitGroupValue(
        parent,
        itemGroup,
        repeatedType.asGroupType(),
        publicName,
        context,
        fieldPath + "." + repeatedType.getName(),
        origin,
        true);
  }

  private static void emitMap(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      GroupType mapType,
      RecordContext context,
      String fieldPath) {

    MapShape shape = mapShape(mapType, fieldPath);
    int wrapperCount = source.getFieldRepetitionCount(fieldIndex);
    for (int wrapperIndex = 0; wrapperIndex < wrapperCount; wrapperIndex++) {
      GroupValueSource wrapper = source.getGroup(fieldIndex, wrapperIndex);
      int entryCount = wrapper.getFieldRepetitionCount(shape.entryIndex());
      for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
        GroupValueSource entry = wrapper.getGroup(shape.entryIndex(), entryIndex);
        String rawKey = mapKey(entry, shape.keyIndex(), shape.keyType(), fieldPath);
        String publicKey = ParquetNames.project(rawKey, fieldPath + ".key");
        context.registerMapKey(publicKey, rawKey, fieldPath);

        if (shape.valueIndex() < 0) {
          addNullNode(parent, publicKey, context, fieldPath + "." + rawKey, OutputOrigin.MAP_KEY, false);
          continue;
        }

        Type valueType = shape.valueType();
        int valueCount = entry.getFieldRepetitionCount(shape.valueIndex());
        if (valueCount == 0) {
          addNullNode(parent, publicKey, context, fieldPath + "." + rawKey, OutputOrigin.MAP_KEY, false);
          continue;
        }
        for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
          emitMapValue(
              parent,
              entry,
              shape.valueIndex(),
              valueIndex,
              valueType,
              publicKey,
              context,
              fieldPath + "." + rawKey);
        }
      }
    }
  }

  private static void emitMapValue(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      Type valueType,
      String publicKey,
      RecordContext context,
      String fieldPath) {

    if (isList(valueType)) {
      emitList(parent, source, fieldIndex, valueType.asGroupType(), publicKey, context, fieldPath, OutputOrigin.MAP_KEY);
    } else if (valueType.isPrimitive()) {
      emitPrimitiveOccurrence(
          parent,
          source,
          fieldIndex,
          valueIndex,
          valueType.asPrimitiveType(),
          publicKey,
          context,
          fieldPath,
          OutputOrigin.MAP_KEY,
          valueType.isRepetition(REPEATED));
    } else if (isScalarStruct(valueType)) {
      emitMapScalarStruct(
          parent,
          source.getGroup(fieldIndex, valueIndex),
          valueType.asGroupType(),
          publicKey,
          context,
          fieldPath);
    } else {
      emitGroupValue(
          parent,
          source.getGroup(fieldIndex, valueIndex),
          valueType.asGroupType(),
          publicKey,
          context,
          fieldPath,
          OutputOrigin.MAP_KEY,
          valueType.isRepetition(REPEATED));
    }
  }

  private static void emitMapScalarStruct(
      Node parent,
      GroupValueSource struct,
      GroupType structType,
      String publicKey,
      RecordContext context,
      String fieldPath) {

    for (int childIndex = 0; childIndex < structType.getFieldCount(); childIndex++) {
      Type childType = structType.getType(childIndex);
      String childPath = fieldPath + "." + childType.getName();
      String childName = publicName(childType, childPath);
      emitPrimitiveField(
          parent,
          struct,
          childIndex,
          childType.asPrimitiveType(),
          publicKey + "_" + childName,
          context,
          childPath,
          OutputOrigin.MAP_KEY,
          false);
    }
  }

  private static void emitElementValue(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      Type type,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin,
      boolean arrayItem) {

    if (type.isPrimitive()) {
      emitPrimitiveOccurrence(
          parent,
          source,
          fieldIndex,
          valueIndex,
          type.asPrimitiveType(),
          publicName,
          context,
          fieldPath,
          origin,
          arrayItem);
    } else {
      emitGroupValue(
          parent,
          source.getGroup(fieldIndex, valueIndex),
          type.asGroupType(),
          publicName,
          context,
          fieldPath,
          origin,
          arrayItem);
    }
  }

  private static void emitGroupValue(
      Node parent,
      GroupValueSource source,
      GroupType groupType,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin,
      boolean arrayItem) {

    Node child = new Node(publicName);
    child.setArrayItem(arrayItem);
    RecordContext childContext = new RecordContext(
        publicName,
        context.recordName(),
        reservedOutputs(groupType, fieldPath),
        context.parameters());
    emitGroupFields(child, source, groupType, childContext, fieldPath);
    context.register(publicName, fieldPath, origin, arrayItem);
    parent.addNode(child);
  }

  private static void emitPrimitiveOccurrence(
      Node parent,
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin,
      boolean arrayItem) {

    ScalarValue value = scalarValue(source, fieldIndex, valueIndex, type, fieldPath);
    Node child = new Node(publicName);
    child.setValue(value.templateString()
        ? Template.expand(value.value(), context.parameters())
        : value.value());
    child.setArrayItem(arrayItem);
    context.register(publicName, fieldPath, origin, arrayItem);
    parent.addNode(child);
  }

  private static void addNullNode(
      Node parent,
      String publicName,
      RecordContext context,
      String fieldPath,
      OutputOrigin origin,
      boolean arrayItem) {

    Node child = new Node(publicName);
    child.setNullValue(true);
    child.setArrayItem(arrayItem);
    context.register(publicName, fieldPath, origin, arrayItem);
    parent.addNode(child);
  }

  private static String mapKey(
      GroupValueSource entry,
      int keyIndex,
      PrimitiveType keyType,
      String fieldPath) {

    if (entry.getFieldRepetitionCount(keyIndex) == 0) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map with missing key at " + fieldPath);
    }
    String key = scalarValue(entry, keyIndex, 0, keyType, fieldPath + ".key").value();
    if (key == null || key.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map with blank key at " + fieldPath);
    }
    return key;
  }

  private static ScalarValue scalarValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      String fieldPath) {

    LogicalTypeAnnotation logical = type.getLogicalTypeAnnotation();
    if (logical instanceof LogicalTypeAnnotation.StringLogicalTypeAnnotation
        || logical instanceof LogicalTypeAnnotation.EnumLogicalTypeAnnotation) {
      return new ScalarValue(source.getBinary(fieldIndex, valueIndex).toStringUsingUTF8(), true);
    }
    if (logical instanceof LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal) {
      return new ScalarValue(decimalValue(source, fieldIndex, valueIndex, type, decimal).toPlainString(), false);
    }
    if (logical instanceof LogicalTypeAnnotation.DateLogicalTypeAnnotation) {
      return new ScalarValue(LocalDate.ofEpochDay(source.getInteger(fieldIndex, valueIndex)).toString(), false);
    }
    if (logical instanceof LogicalTypeAnnotation.TimeLogicalTypeAnnotation time) {
      return new ScalarValue(timeValue(source, fieldIndex, valueIndex, type, time), false);
    }
    if (logical instanceof LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestamp) {
      return new ScalarValue(timestampValue(source, fieldIndex, valueIndex, timestamp), false);
    }
    if (unsupportedLogicalType(logical)) {
      return Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet logical type at " + fieldPath + ": " + logical);
    }

    return primitiveValue(source, fieldIndex, valueIndex, type, fieldPath);
  }

  private static boolean unsupportedLogicalType(LogicalTypeAnnotation logical) {
    return logical != null
        && !(logical instanceof LogicalTypeAnnotation.IntLogicalTypeAnnotation);
  }

  private static ScalarValue primitiveValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      String fieldPath) {

    return switch (type.getPrimitiveTypeName()) {
      case BOOLEAN -> new ScalarValue(Boolean.toString(source.getBoolean(fieldIndex, valueIndex)), false);
      case INT32 -> new ScalarValue(Integer.toString(source.getInteger(fieldIndex, valueIndex)), false);
      case INT64 -> new ScalarValue(Long.toString(source.getLong(fieldIndex, valueIndex)), false);
      case FLOAT -> new ScalarValue(Float.toString(source.getFloat(fieldIndex, valueIndex)), false);
      case DOUBLE -> new ScalarValue(Double.toString(source.getDouble(fieldIndex, valueIndex)), false);
      case BINARY, FIXED_LEN_BYTE_ARRAY -> new ScalarValue(
          Base64.getEncoder().encodeToString(source.getBinary(fieldIndex, valueIndex).getBytes()),
          false);
      case INT96 -> new ScalarValue(
          Base64.getEncoder().encodeToString(source.getInt96(fieldIndex, valueIndex).getBytes()),
          false);
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet primitive type at " + fieldPath + ": " + type.getPrimitiveTypeName());
    };
  }

  private static BigDecimal decimalValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      LogicalTypeAnnotation.DecimalLogicalTypeAnnotation decimal) {

    BigInteger unscaled = switch (type.getPrimitiveTypeName()) {
      case INT32 -> BigInteger.valueOf(source.getInteger(fieldIndex, valueIndex));
      case INT64 -> BigInteger.valueOf(source.getLong(fieldIndex, valueIndex));
      case BINARY, FIXED_LEN_BYTE_ARRAY -> new BigInteger(source.getBinary(fieldIndex, valueIndex).getBytes());
      default -> Log.fatal(
          IllegalArgumentException.class,
          "Unsupported Parquet decimal physical type: " + type.getPrimitiveTypeName());
    };
    return new BigDecimal(unscaled, decimal.getScale());
  }

  private static String timeValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      PrimitiveType type,
      LogicalTypeAnnotation.TimeLogicalTypeAnnotation time) {

    long raw = type.getPrimitiveTypeName() == PrimitiveType.PrimitiveTypeName.INT32
        ? source.getInteger(fieldIndex, valueIndex)
        : source.getLong(fieldIndex, valueIndex);
    long nanos = switch (time.getUnit()) {
      case MILLIS -> raw * 1_000_000L;
      case MICROS -> raw * 1_000L;
      case NANOS -> raw;
    };
    return LocalTime.ofNanoOfDay(nanos).toString();
  }

  private static String timestampValue(
      GroupValueSource source,
      int fieldIndex,
      int valueIndex,
      LogicalTypeAnnotation.TimestampLogicalTypeAnnotation timestamp) {

    long raw = source.getLong(fieldIndex, valueIndex);
    long seconds = switch (timestamp.getUnit()) {
      case MILLIS -> raw / 1_000L;
      case MICROS -> raw / 1_000_000L;
      case NANOS -> raw / 1_000_000_000L;
    };
    long nanos = switch (timestamp.getUnit()) {
      case MILLIS -> Math.floorMod(raw, 1_000L) * 1_000_000L;
      case MICROS -> Math.floorMod(raw, 1_000_000L) * 1_000L;
      case NANOS -> Math.floorMod(raw, 1_000_000_000L);
    };
    if (timestamp.isAdjustedToUTC()) {
      return Instant.ofEpochSecond(seconds, nanos).toString();
    }
    return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC).toString();
  }

  private static boolean isScalarStruct(Type type) {
    if (type.isPrimitive() || type.isRepetition(REPEATED) || isMap(type) || isList(type)) {
      return false;
    }
    GroupType groupType = type.asGroupType();
    if (groupType.getFieldCount() == 0) {
      return false;
    }
    for (Type child : groupType.getFields()) {
      if (!child.isPrimitive() || child.isRepetition(REPEATED)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isMap(Type type) {
    OriginalType original = type.getOriginalType();
    return original == OriginalType.MAP
        || original == OriginalType.MAP_KEY_VALUE
        || type.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation;
  }

  private static boolean isList(Type type) {
    return type.getOriginalType() == OriginalType.LIST
        || type.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation;
  }

  private static MapShape mapShape(GroupType mapType, String fieldPath) {
    if (mapType.getFieldCount() != 1) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map shape at " + fieldPath);
    }
    Type entryType = mapType.getType(0);
    if (entryType.isPrimitive() || !entryType.isRepetition(REPEATED)) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map entries at " + fieldPath);
    }

    GroupType entryGroup = entryType.asGroupType();
    if (entryGroup.getFieldCount() < 1 || entryGroup.getFieldCount() > 2) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map entry shape at " + fieldPath);
    }
    Type keyType = entryGroup.getType(0);
    if (!keyType.isPrimitive()) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet map key type at " + fieldPath);
    }
    int valueIndex = entryGroup.getFieldCount() == 2 ? 1 : -1;
    return new MapShape(0, 0, keyType.asPrimitiveType(), valueIndex, valueIndex < 0 ? null : entryGroup.getType(valueIndex));
  }

  private static ListShape listShape(GroupType listType, String fieldPath) {
    if (listType.getFieldCount() != 1) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet list shape at " + fieldPath);
    }
    Type repeatedType = listType.getType(0);
    if (!repeatedType.isRepetition(REPEATED)) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported Parquet list entries at " + fieldPath);
    }
    if (repeatedType.isPrimitive()) {
      return new ListShape(0, repeatedType, false, -1, null);
    }

    GroupType repeatedGroup = repeatedType.asGroupType();
    if (repeatedGroup.getFieldCount() == 1
        && ("list".equals(repeatedType.getName()) || "array".equals(repeatedType.getName()))) {
      return new ListShape(0, repeatedType, true, 0, repeatedGroup.getType(0));
    }
    return new ListShape(0, repeatedType, false, -1, null);
  }

  private static String publicName(Type type, String sourcePath) {
    return ParquetNames.project(type.getName(), sourcePath);
  }

  private record PublicNames(String root, String record) {
  }

  private record ScalarValue(String value, boolean templateString) {
  }

  private record MapShape(
      int entryIndex,
      int keyIndex,
      PrimitiveType keyType,
      int valueIndex,
      Type valueType) {
  }

  private record ListShape(
      int repeatedIndex,
      Type repeatedType,
      boolean unwrapElement,
      int elementIndex,
      Type elementType) {
  }

  private enum OutputOrigin {
    DIRECT(false, false),
    STRUCT_FLATTENED(true, false),
    MAP_KEY(true, true);

    private final boolean storageDerived;
    private final boolean mapDerived;

    OutputOrigin(boolean storageDerived, boolean mapDerived) {
      this.storageDerived = storageDerived;
      this.mapDerived = mapDerived;
    }
  }

  private static final class RecordContext {
    private final String recordName;
    private final String parentRecordName;
    private final Map<String, String> reservedOutputs;
    private final Map<String, String> emittedOutputs = new LinkedHashMap<>();
    private final Map<String, String> mapKeys = new LinkedHashMap<>();
    private final Map<String, String> parameters;

    private RecordContext(String recordName, String parentRecordName, Map<String, String> reservedOutputs) {
      this(recordName, parentRecordName, reservedOutputs, Map.of());
    }

    private RecordContext(
        String recordName,
        String parentRecordName,
        Map<String, String> reservedOutputs,
        Map<String, String> parameters) {
      this.recordName = recordName;
      this.parentRecordName = parentRecordName;
      this.reservedOutputs = Map.copyOf(reservedOutputs);
      this.parameters = parameters == null ? Map.of() : parameters;
    }

    private String recordName() {
      return recordName;
    }

    private Map<String, String> parameters() {
      return parameters;
    }

    private void registerMapKey(String publicKey, String rawKey, String fieldPath) {
      String previous = mapKeys.putIfAbsent(publicKey, rawKey);
      if (previous != null) {
        Log.fatal(
            IllegalArgumentException.class,
            "Unsupported Parquet map duplicate key ["
                + publicKey
                + "] on record ["
                + recordName
                + "] from ["
                + previous
                + "] and ["
                + rawKey
                + "]");
      }
      String reserved = reservedOutputs.get(publicKey);
      if (reserved != null) {
        Log.fatal(
            IllegalArgumentException.class,
            "Unsupported Parquet map key ["
                + publicKey
                + "] on record ["
                + recordName
                + "] collides with direct child ["
                + reserved
                + "] from ["
                + fieldPath
                + "]");
      }
      validateRelationshipCollision(publicKey, fieldPath, OutputOrigin.MAP_KEY);
    }

    private void register(String publicName, String fieldPath, OutputOrigin origin, boolean repeated) {
      if (origin.mapDerived) {
        String reserved = reservedOutputs.get(publicName);
        if (reserved != null) {
          Log.fatal(
              IllegalArgumentException.class,
              "Unsupported Parquet map output ["
                  + publicName
                  + "] on record ["
                  + recordName
                  + "] collides with direct child ["
                  + reserved
                  + "] from ["
                  + fieldPath
                  + "]");
        }
      }
      validateRelationshipCollision(publicName, fieldPath, origin);

      String previous = emittedOutputs.putIfAbsent(publicName, fieldPath);
      if (previous != null && !repeated) {
        Log.fatal(
            IllegalArgumentException.class,
            "Parquet output name collision for ["
                + publicName
                + "] on record ["
                + recordName
                + "] between ["
                + previous
                + "] and ["
                + fieldPath
                + "]");
      }
    }

    private void validateRelationshipCollision(String publicName, String fieldPath, OutputOrigin origin) {
      if (!origin.storageDerived || parentRecordName == null) {
        return;
      }
      String generatedParentColumn = parentRecordName + "_id";
      if (generatedParentColumn.equals(publicName)) {
        Log.fatal(
            IllegalArgumentException.class,
            "Parquet flattened field ["
                + publicName
                + "] on record ["
                + recordName
                + "] collides with generated cache relationship column ["
                + generatedParentColumn
                + "] from ["
                + fieldPath
                + "]");
      }
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

  public static final class ParquetNames {
    private ParquetNames() {
    }

    public static String project(String rawName, String sourcePath) {
      if (rawName == null || rawName.isBlank()) {
        return Log.fatal(
            IllegalArgumentException.class,
            "Parquet name is blank at " + sourcePath);
      }

      StringBuilder projected = new StringBuilder(rawName.length() + 1);
      boolean previousWasReplacement = false;
      boolean hasUsableCharacter = false;
      for (int index = 0; index < rawName.length(); index++) {
        char ch = rawName.charAt(index);
        if (isPathPartCharacter(ch)) {
          projected.append(ch);
          previousWasReplacement = false;
          hasUsableCharacter = true;
        } else if (!previousWasReplacement) {
          projected.append('_');
          previousWasReplacement = true;
        }
      }

      if (!hasUsableCharacter) {
        return Log.fatal(
            IllegalArgumentException.class,
            "Parquet name [" + rawName + "] has no usable path characters at " + sourcePath);
      }
      if (projected.isEmpty()) {
        return Log.fatal(
            IllegalArgumentException.class,
            "Parquet name [" + rawName + "] projects to an empty public name at " + sourcePath);
      }
      if (!isPathPartStart(projected.charAt(0))) {
        projected.insert(0, '_');
      }
      return projected.toString();
    }

    public static void validateProjectedSiblingNames(Iterable<OriginalName> names, String scopePath) {
      Map<String, String> originalsByPublicName = new LinkedHashMap<>();
      for (OriginalName name : names) {
        String publicName = project(name.rawName(), name.sourcePath());
        String previous = originalsByPublicName.putIfAbsent(publicName, name.sourcePath());
        if (previous != null) {
          Log.fatal(
              IllegalArgumentException.class,
              "Parquet projected name collision for ["
                  + publicName
                  + "] in ["
                  + scopePath
                  + "] between ["
                  + previous
                  + "] and ["
                  + name.sourcePath()
                  + "]");
        }
      }
    }

    private static boolean isPathPartStart(char ch) {
      return isAsciiLetter(ch) || ch == '_' || ch == '$';
    }

    private static boolean isPathPartCharacter(char ch) {
      return isPathPartStart(ch) || isAsciiDigit(ch);
    }

    private static boolean isAsciiLetter(char ch) {
      return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isAsciiDigit(char ch) {
      return ch >= '0' && ch <= '9';
    }

    public record OriginalName(String rawName, String sourcePath) {
    }
  }
}
