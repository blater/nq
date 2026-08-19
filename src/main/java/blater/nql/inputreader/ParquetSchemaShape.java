package blater.nql.inputreader;

import blater.nql.inputreader.ParquetInputReader.ParquetNames;
import blater.nql.util.Log;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import static org.apache.parquet.schema.Type.Repetition.REPEATED;

/** Recognises the supported structural encodings in a Parquet schema. */
final class ParquetSchemaShape {
  private ParquetSchemaShape() {
  }

  static boolean isScalarStruct(Type type) {
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

  static boolean isMap(Type type) {
    OriginalType original = type.getOriginalType();
    return original == OriginalType.MAP
        || original == OriginalType.MAP_KEY_VALUE
        || type.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.MapLogicalTypeAnnotation;
  }

  static boolean isList(Type type) {
    return type.getOriginalType() == OriginalType.LIST
        || type.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation;
  }

  static MapShape mapShape(GroupType mapType, String fieldPath) {
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
    return new MapShape(
        0, 0, keyType.asPrimitiveType(), valueIndex, valueIndex < 0 ? null : entryGroup.getType(valueIndex));
  }

  static ListShape listShape(GroupType listType, String fieldPath) {
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

  static String publicName(Type type, String sourcePath) {
    return ParquetNames.project(type.getName(), sourcePath);
  }

  record MapShape(
      int entryIndex,
      int keyIndex,
      PrimitiveType keyType,
      int valueIndex,
      Type valueType) {
  }

  record ListShape(
      int repeatedIndex,
      Type repeatedType,
      boolean unwrapElement,
      int elementIndex,
      Type elementType) {
  }
}
