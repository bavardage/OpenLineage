package io.openlineage.spark3.agent.lifecycle.plan.columnLineage;

import io.openlineage.spark.agent.util.DatasetIdentifier;
import io.openlineage.spark.agent.util.PlanUtils;
import io.openlineage.spark.agent.util.ScalaConversionUtils;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark3.agent.utils.PlanUtils3;
import java.net.URI;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.catalyst.catalog.HiveTableRelation;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.apache.spark.sql.catalyst.plans.logical.UnaryNode;
import org.apache.spark.sql.execution.datasources.LogicalRelation;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation;
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2ScanRelation;

/** Traverses LogicalPlan and collect input fields with the corresponding ExprId. */
@Slf4j
class InputFieldsCollector {

  private final LogicalPlan plan;
  private final OpenLineageContext context;

  InputFieldsCollector(LogicalPlan plan, OpenLineageContext context) {
    this.plan = plan;
    this.context = context;
  }

  void collect(ColumnLevelLineageBuilder builder) {
    collect(plan, builder);
  }

  void collect(LogicalPlan node, ColumnLevelLineageBuilder builder) {
    discoverInputsFromNode(node, builder);

    // hacky way to replace `node instanceof UnaryNode` which fails for Spark 3.2.1
    // because of java.lang.IncompatibleClassChangeError: UnaryNode, but class was expected
    // probably related to single code base for different Spark versions
    if ((node.getClass()).isAssignableFrom(UnaryNode.class)) {
      collect(((UnaryNode) node).child(), builder);
    } else if (node.children() != null) {
      ScalaConversionUtils.<LogicalPlan>fromSeq(node.children()).stream()
          .forEach(child -> collect(child, builder));
    }
  }

  private void discoverInputsFromNode(LogicalPlan node, ColumnLevelLineageBuilder builder) {
    extractDatasetIdentifier(node)
        .ifPresent(
            di ->
                ScalaConversionUtils.fromSeq(node.output()).stream()
                    .filter(attr -> attr instanceof AttributeReference)
                    .map(attr -> (AttributeReference) attr)
                    .forEach(attr -> builder.addInput(attr.exprId(), di, attr.name())));
  }

  private Optional<DatasetIdentifier> extractDatasetIdentifier(LogicalPlan node) {
    if (node instanceof DataSourceV2Relation) {
      return extractDatasetIdentifier((DataSourceV2Relation) node);
    } else if (node instanceof DataSourceV2ScanRelation) {
      return extractDatasetIdentifier(((DataSourceV2ScanRelation) node).relation());
    } else if (node instanceof HiveTableRelation) {
      return extractDatasetIdentifier(((HiveTableRelation) node).tableMeta());
    } else if (node instanceof LogicalRelation
        && ((LogicalRelation) node).catalogTable().isDefined()) {
      return extractDatasetIdentifier(((LogicalRelation) node).catalogTable().get());
    } else {
      // hmy -> leaf node we don't understand
      log.info("Not able to recognize column lineage for LeafNode: {}", node.getClass().getName());
    }

    return Optional.empty();
  }

  private Optional<DatasetIdentifier> extractDatasetIdentifier(DataSourceV2Relation relation) {
    return PlanUtils3.getDatasetIdentifier(context, relation);
  }

  private Optional<DatasetIdentifier> extractDatasetIdentifier(CatalogTable catalogTable) {
    URI location = catalogTable.location();
    if (location == null) {
      return Optional.empty();
    } else {
      return Optional.of(
          new DatasetIdentifier(
              catalogTable.location().getPath(), PlanUtils.namespaceUri(catalogTable.location())));
    }
  }
}
