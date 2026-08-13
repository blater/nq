package blater.nq.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Collections.emptyList;

/*
 * Responsibility: Holds the fields and keyed paths used to map
 * flat query rows into one result hierarchy.
 */
@NoArgsConstructor
public class MappingPlan{
  @Getter
  private List<OutputField> fields = emptyList();
  @Getter
  private List<KeyedPath> keyedPaths = emptyList();

  public MappingPlan(List<OutputField> fields, List<KeyedPath> keyedPaths) {
    this.fields = fields;
    this.keyedPaths = keyedPaths;
  }

  public static MappingPlan flatRows(List<String> sourceColumns, List<String> outputNames) {
    if (sourceColumns.size() != outputNames.size()) {
      throw new IllegalArgumentException("SELECT output names do not match its result columns.");
    }
    List<OutputField> fields = java.util.stream.IntStream.range(0, sourceColumns.size())
        .mapToObj(index -> {
          String outputName = outputNames.get(index);
          if (outputName == null || outputName.isBlank()) outputName = sourceColumns.get(index);
          return new OutputField(
              new HierarchyPath(List.of(outputName)), sourceColumns.get(index), null, List.of(), false);
        })
        .toList();
    return new MappingPlan(fields, List.of());
  }

  public String rootName() {
    if (!fields.isEmpty()) {
      return fields.getFirst().getPath().getRootName();
    }
    return null;
  }

}
