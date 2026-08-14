package blater.nq.inputreader;

import blater.nq.domain.Hierarchy;
import blater.nq.domain.Node;
import blater.nq.util.Log;
import blater.nq.util.Template;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.GroupValueSource;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.util.*;

import static blater.nq.inputreader.ParquetFileSchema.reservedOutputs;
import static blater.nq.inputreader.ParquetScalarDecoder.mapKey;
import static blater.nq.inputreader.ParquetScalarDecoder.scalarValue;
import static blater.nq.inputreader.ParquetSchemaShape.isList;
import static blater.nq.inputreader.ParquetSchemaShape.isMap;
import static blater.nq.inputreader.ParquetSchemaShape.isScalarStruct;
import static blater.nq.inputreader.ParquetSchemaShape.listShape;
import static blater.nq.inputreader.ParquetSchemaShape.mapShape;
import static blater.nq.inputreader.ParquetSchemaShape.publicName;
import static org.apache.parquet.schema.Type.Repetition.REPEATED;

public class ParquetInputReader implements InputReader {
  @Override
  public Hierarchy load(String filename, Map<String, String> parameters) {
    return ParquetHierarchyLoader.load(filename, parameters);
  }

  static Node emitRecord(
      Group record,
      MessageType schema,
      String recordName,
      Map<String, String> parameters) {

    Node recordNode = new Node(recordName);
    recordNode.setArrayItem(true);
    RecordContext context = new RecordContext(
        recordName, null, reservedOutputs(schema, schema.getName()), parameters);
    emitGroupFields(recordNode, record, schema, context, schema.getName());
    return recordNode;
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
      ParquetScalarDecoder.ScalarValue value = scalarValue(source, fieldIndex, valueIndex, type, fieldPath);
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

    ParquetSchemaShape.ListShape shape = listShape(listType, fieldPath);
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
      ParquetSchemaShape.ListShape shape,
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

    ParquetSchemaShape.MapShape shape = mapShape(mapType, fieldPath);
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

    ParquetScalarDecoder.ScalarValue value = scalarValue(source, fieldIndex, valueIndex, type, fieldPath);
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
