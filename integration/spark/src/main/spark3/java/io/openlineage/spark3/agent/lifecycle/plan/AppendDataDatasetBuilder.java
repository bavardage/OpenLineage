/* SPDX-License-Identifier: Apache-2.0 */

package io.openlineage.spark3.agent.lifecycle.plan;

import io.openlineage.client.OpenLineage;
import io.openlineage.spark.api.AbstractQueryPlanOutputDatasetBuilder;
import io.openlineage.spark.api.DatasetFactory;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark3.agent.lifecycle.plan.columnLineage.ColumnLevelLineageUtils;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.catalyst.plans.logical.AppendData;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;

/**
 * {@link LogicalPlan} visitor that matches an {@link AppendData} commands and extracts the output
 * {@link OpenLineage.Dataset} being written.
 */
@Slf4j
public class AppendDataDatasetBuilder extends AbstractQueryPlanOutputDatasetBuilder<AppendData> {

  private final DatasetFactory<OpenLineage.OutputDataset> factory;

  public AppendDataDatasetBuilder(
      OpenLineageContext context, DatasetFactory<OpenLineage.OutputDataset> factory) {
    super(context, false);
    this.factory = factory;
  }

  @Override
  public boolean isDefinedAtLogicalPlan(LogicalPlan logicalPlan) {
    return logicalPlan instanceof AppendData;
  }

  @Override
  public List<OpenLineage.OutputDataset> apply(AppendData x) {
    // Needs to cast to logical plan despite IntelliJ claiming otherwise.
    LogicalPlan logicalPlan = (LogicalPlan) ((AppendData) x).table();

    if (logicalPlan instanceof DataSourceV2Relation) {
      DataSourceV2Relation relation = (DataSourceV2Relation) logicalPlan;
      Optional<OpenLineage.OutputDataset> outputDataset =
          new DataSourceV2RelationOutputDatasetBuilder(context, factory)
              .apply((DataSourceV2Relation) logicalPlan).stream().findFirst();

      return ColumnLevelLineageUtils.buildColumnLineageDatasetFacet(context, (relation).schema())
          .filter(facet -> outputDataset.isPresent())
          .map(
              columnFacet ->
                  ColumnLevelLineageUtils.rewriteOutputDataset(outputDataset.get(), columnFacet))
          .map(el -> Collections.singletonList(el))
          .orElse(Collections.emptyList());
    } else {
      return Collections.emptyList();
    }
  }
}
