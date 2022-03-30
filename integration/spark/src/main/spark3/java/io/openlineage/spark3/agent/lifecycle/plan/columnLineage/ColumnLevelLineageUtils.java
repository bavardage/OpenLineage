package io.openlineage.spark3.agent.lifecycle.plan.columnLineage;

import io.openlineage.client.OpenLineage;
import io.openlineage.spark.agent.util.DatasetIdentifier;
import io.openlineage.spark.api.OpenLineageContext;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.types.StructType;

/**
 * Utility functions for detecting column level lineage within {@link
 * org.apache.spark.sql.catalyst.plans.logical.LogicalPlan}.
 */
@Slf4j
public class ColumnLevelLineageUtils {

  public static Optional<OpenLineage.ColumnLineageDatasetFacet> buildColumnLineageDatasetFacet(
      OpenLineageContext context, StructType outputSchema) {
    if (!context.getQueryExecution().isPresent()
        || context.getQueryExecution().get().optimizedPlan() == null) {
      return Optional.empty();
    }

    ColumnLevelLineageBuilder builder = new ColumnLevelLineageBuilder(outputSchema);
    LogicalPlan plan = context.getQueryExecution().get().optimizedPlan();

    new FieldDependenciesCollector(plan).collect(builder);
    new OutputFieldsCollector(plan).collect(builder);
    new InputFieldsCollector(plan, context).collect(builder);

    OpenLineage.ColumnLineageDatasetFacetFieldsBuilder fieldsBuilder =
        context.getOpenLineage().newColumnLineageDatasetFacetFieldsBuilder();

    // FIXME: code below should be moved to ColumnLevelLineageBuilder class
    Arrays.stream(outputSchema.fields())
        .forEach(
            field -> {
              List<Pair<DatasetIdentifier, String>> inputs = builder.getInputsUsedFor(field.name());
              if (!inputs.isEmpty()) {
                fieldsBuilder.put(
                    field.name(),
                    inputs.stream()
                        .map(
                            pair ->
                                context
                                    .getOpenLineage()
                                    .newColumnLineageDatasetFacetFieldsAdditionalBuilder()
                                    .namespace(pair.getLeft().getNamespace())
                                    .name(pair.getLeft().getName())
                                    .field(pair.getRight())
                                    .build())
                        .collect(Collectors.toList()));
              }
            });

    OpenLineage.ColumnLineageDatasetFacetBuilder facetBuilder =
        context.getOpenLineage().newColumnLineageDatasetFacetBuilder();

    facetBuilder.fields(fieldsBuilder.build());
    OpenLineage.ColumnLineageDatasetFacet facet = facetBuilder.build();

    if (facet.getFields().getAdditionalProperties().isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(facetBuilder.build());
    }
  }
}
