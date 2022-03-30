package io.openlineage.spark3.agent.lifecycle.plan.columnLineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openlineage.spark.agent.util.DatasetIdentifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.spark.sql.catalyst.expressions.ExprId;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;

@Slf4j
class ColumnLevelLineageBuilder {

  private Map<ExprId, List<ExprId>> exprDependencies = new HashMap<>();
  private Map<ExprId, List<Pair<DatasetIdentifier, String>>> inputs = new HashMap<>();
  private Map<StructField, ExprId> outputs = new HashMap<>();
  private final StructType schema;

  ColumnLevelLineageBuilder(StructType schema) {
    this.schema = schema;
  }

  void addInput(ExprId exprId, DatasetIdentifier datasetIdentifier, String attributeName) {
    if (!inputs.containsKey(exprId)) {
      inputs.put(exprId, new LinkedList<>());
    }
    inputs.get(exprId).add(Pair.of(datasetIdentifier, attributeName));
  }

  void addOutput(ExprId exprId, String attributeName) {
    Arrays.stream(schema.fields())
        .filter(field -> field.name().equals(attributeName))
        .findAny()
        .ifPresent(field -> outputs.put(field, exprId));
  }

  /**
   * Evaluation of parent requires exprIdB
   *
   * @param parent
   * @param child
   */
  void addDependency(ExprId parent, ExprId child) {
    if (!exprDependencies.containsKey(parent)) {
      exprDependencies.put(parent, new LinkedList<>());
    }
    exprDependencies.get(parent).add(child);
  }

  public boolean hasOutputs() {
    return !outputs.isEmpty();
  }

  @SneakyThrows
  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    ObjectMapper mapper = new ObjectMapper();
    sb.append("Inputs: ").append(mapper.writeValueAsString(inputs)).append(System.lineSeparator());
    sb.append("Dependencies: ")
        .append(
            mapper.writeValueAsString(
                exprDependencies.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.toString())) // need to call toString method explicitly
                ))
        .append(System.lineSeparator());

    sb.append("Outputs: ")
        .append(
            mapper.writeValueAsString(
                outputs.entrySet().stream()
                    .collect(
                        Collectors.toMap(
                            Map.Entry::getKey,
                            e -> e.toString())) // need to call toString method explicitly
                ))
        .append(System.lineSeparator());

    return sb.toString();
  }

  List<Pair<DatasetIdentifier, String>> getInputsUsedFor(String outputName) {
    Optional<StructField> outputField =
        Arrays.stream(schema.fields())
            .filter(field -> field.name().equalsIgnoreCase(outputName))
            .findAny();

    if (!outputField.isPresent() || !outputs.containsKey(outputField.get())) {
      return Collections.emptyList();
    }

    return findDependentInputs(outputs.get(outputField.get())).stream()
        .filter(inputExprId -> inputs.containsKey(inputExprId))
        .flatMap(inputExprId -> inputs.get(inputExprId).stream())
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private List<ExprId> findDependentInputs(ExprId outputExprId) {
    List<ExprId> dependentInputs = new LinkedList<>();
    dependentInputs.add(outputExprId);
    boolean continueSearch = true;

    while (continueSearch) {
      Set<ExprId> newDependentInputs =
          dependentInputs.stream()
              .filter(exprId -> exprDependencies.containsKey(exprId))
              .flatMap(exprId -> exprDependencies.get(exprId).stream())
              .filter(exprId -> !dependentInputs.contains(exprId)) // filter already added
              .collect(Collectors.toSet());

      dependentInputs.addAll(newDependentInputs);
      continueSearch = !newDependentInputs.isEmpty();
    }

    return dependentInputs;
  }
}
