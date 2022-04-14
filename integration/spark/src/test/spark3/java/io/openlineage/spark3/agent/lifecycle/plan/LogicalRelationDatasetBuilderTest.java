package io.openlineage.spark3.agent.lifecycle.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.openlineage.client.OpenLineage;
import io.openlineage.spark.agent.client.OpenLineageClient;
import io.openlineage.spark.agent.util.PlanUtils;
import io.openlineage.spark.api.DatasetFactory;
import io.openlineage.spark.api.OpenLineageContext;
import io.openlineage.spark3.agent.lifecycle.plan.columnLineage.ColumnLevelLineageUtils;
import io.openlineage.spark3.agent.utils.DatasetVersionDatasetFacetUtils;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.catalog.CatalogTable;
import org.apache.spark.sql.execution.datasources.FileIndex;
import org.apache.spark.sql.execution.datasources.HadoopFsRelation;
import org.apache.spark.sql.execution.datasources.LogicalRelation;
import org.apache.spark.sql.internal.SessionState;
import org.apache.spark.sql.sources.BaseRelation;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scala.Option;
import scala.collection.Seq$;

public class LogicalRelationDatasetBuilderTest {

  private static final String SOME_VERSION = "version_1";

  OpenLineageContext openLineageContext = mock(OpenLineageContext.class);
  SparkSession session = mock(SparkSession.class);
  LogicalRelationDatasetBuilder visitor =
      new LogicalRelationDatasetBuilder(
          openLineageContext, DatasetFactory.output(openLineageContext), false);
  OpenLineage.DatasetVersionDatasetFacet facet = mock(OpenLineage.DatasetVersionDatasetFacet.class);
  OpenLineage openLineage = mock(OpenLineage.class);
  SparkContext sparkContext = mock(SparkContext.class);
  LogicalRelation logicalRelation = mock(LogicalRelation.class);
  StructType schema = mock(StructType.class);
  HadoopFsRelation hadoopFsRelation = mock(HadoopFsRelation.class);
  Configuration hadoopConfig = mock(Configuration.class);
  SessionState sessionState = mock(SessionState.class);
  FileIndex fileIndex = mock(FileIndex.class);
  Path path = new Path("/tmp/path1");

  @BeforeEach
  public void setup() {
    when(openLineageContext.getSparkContext()).thenReturn(sparkContext);
    when(openLineageContext.getSparkSession()).thenReturn(Optional.of(session));
    when(openLineageContext.getOpenLineage())
        .thenReturn(new OpenLineage(OpenLineageClient.OPEN_LINEAGE_CLIENT_URI));
    when(logicalRelation.relation()).thenReturn(hadoopFsRelation);
    when(facet.getDatasetVersion()).thenReturn(SOME_VERSION);
    when(session.sessionState()).thenReturn(sessionState);
    when(sessionState.newHadoopConfWithOptions(any())).thenReturn(hadoopConfig);
    when(hadoopFsRelation.location()).thenReturn(fileIndex);
    when(fileIndex.rootPaths())
        .thenReturn(
            scala.collection.JavaConverters.collectionAsScalaIterableConverter(Arrays.asList(path))
                .asScala()
                .toSeq());
    when(openLineage.newDatasetFacetsBuilder()).thenReturn(new OpenLineage.DatasetFacetsBuilder());
    when(openLineage.newDatasetVersionDatasetFacet(SOME_VERSION)).thenReturn(facet);
  }

  @Test
  void testApplyForHadoopFsRelationDatasetVersionFacet() {
    try (MockedStatic mocked = mockStatic(PlanUtils.class)) {
      try (MockedStatic mockedFacetUtils = mockStatic(DatasetVersionDatasetFacetUtils.class)) {
        when(PlanUtils.getDirectoryPath(path, hadoopConfig)).thenReturn(new Path("/tmp"));
        when(DatasetVersionDatasetFacetUtils.extractVersionFromLogicalRelation(logicalRelation))
            .thenReturn(Optional.of(SOME_VERSION));

        List<OpenLineage.Dataset> datasets = visitor.apply(logicalRelation);
        assertEquals(1, datasets.size());
        OpenLineage.Dataset ds = datasets.get(0);
        assertEquals("/tmp", ds.getName());
        assertEquals(SOME_VERSION, ds.getFacets().getVersion().getDatasetVersion());
      }
    }
  }

  @Test
  public void testApplyWhenNoDatasetsReturnedFromParentClass() {
    try (MockedStatic mocked = mockStatic(PlanUtils.class)) {
      try (MockedStatic mockedFacetUtils = mockStatic(DatasetVersionDatasetFacetUtils.class)) {
        when(PlanUtils.getDirectoryPath(path, hadoopConfig)).thenReturn(new Path("/tmp"));
        when(DatasetVersionDatasetFacetUtils.extractVersionFromLogicalRelation(logicalRelation))
            .thenReturn(Optional.empty());
        when(fileIndex.rootPaths()).thenReturn(Seq$.MODULE$.<String>empty());

        assertEquals(0, visitor.apply(logicalRelation).size());
      }
    }
  }

  @Test
  public void testApplForInputDataset() {
    LogicalRelationDatasetBuilder inputVisitor =
        new LogicalRelationDatasetBuilder(
            openLineageContext, DatasetFactory.input(openLineageContext), false);

    try (MockedStatic mocked = mockStatic(PlanUtils.class)) {
      try (MockedStatic mockedFacetUtils = mockStatic(DatasetVersionDatasetFacetUtils.class)) {
        when(PlanUtils.getDirectoryPath(path, hadoopConfig)).thenReturn(new Path("/tmp"));
        when(DatasetVersionDatasetFacetUtils.extractVersionFromLogicalRelation(logicalRelation))
            .thenReturn(Optional.empty());

        assertEquals(1, inputVisitor.apply(logicalRelation).size());
      }
    }
  }

  @Test
  public void testApplyColumnLineageIncludedForHadoopFsRelation() {
    OpenLineage.ColumnLineageDatasetFacet columnLineageDatasetFacet =
        mock(OpenLineage.ColumnLineageDatasetFacet.class);
    OpenLineage.OutputDataset outputDataset = mock(OpenLineage.OutputDataset.class);
    try (MockedStatic mocked = mockStatic(PlanUtils.class)) {
      try (MockedStatic mockedFacetUtils = mockStatic(DatasetVersionDatasetFacetUtils.class)) {
        try (MockedStatic mockedColumnLineage = mockStatic(ColumnLevelLineageUtils.class)) {
          when(hadoopFsRelation.schema()).thenReturn(schema);
          when(PlanUtils.getDirectoryPath(path, hadoopConfig)).thenReturn(new Path("/tmp"));
          when(DatasetVersionDatasetFacetUtils.extractVersionFromLogicalRelation(logicalRelation))
              .thenReturn(Optional.of(SOME_VERSION));
          when(ColumnLevelLineageUtils.buildColumnLineageDatasetFacet(openLineageContext, schema))
              .thenReturn(Optional.of(columnLineageDatasetFacet));
          when(ColumnLevelLineageUtils.rewriteOutputDataset(any(), eq(columnLineageDatasetFacet)))
              .thenReturn(outputDataset);

          assertEquals(
              outputDataset, (OpenLineage.OutputDataset) visitor.apply(logicalRelation).get(0));
        }
      }
    }
  }

  @Test
  public void testApplyColumnLineageIncludedWhenCatalogTableDefined() {
    CatalogTable catalogTable = mock(CatalogTable.class);
    DatasetFactory datasetFactory = mock(DatasetFactory.class);
    OpenLineage.OutputDataset outputDataset = mock(OpenLineage.OutputDataset.class);

    when(logicalRelation.relation()).thenReturn(mock(BaseRelation.class));
    when(logicalRelation.catalogTable()).thenReturn(Option.apply(catalogTable));
    when(catalogTable.schema()).thenReturn(schema);
    when(datasetFactory.getDataset((URI) any(), any())).thenReturn(outputDataset);

    LogicalRelationDatasetBuilder visitor =
        new LogicalRelationDatasetBuilder(openLineageContext, datasetFactory, false);

    OpenLineage.ColumnLineageDatasetFacet columnLineageDatasetFacet =
        mock(OpenLineage.ColumnLineageDatasetFacet.class);
    try (MockedStatic mocked = mockStatic(PlanUtils.class)) {
      try (MockedStatic mockedFacetUtils = mockStatic(DatasetVersionDatasetFacetUtils.class)) {
        try (MockedStatic mockedColumnLineage = mockStatic(ColumnLevelLineageUtils.class)) {
          when(PlanUtils.getDirectoryPath(path, hadoopConfig)).thenReturn(new Path("/tmp"));
          when(DatasetVersionDatasetFacetUtils.extractVersionFromLogicalRelation(logicalRelation))
              .thenReturn(Optional.of(SOME_VERSION));
          when(ColumnLevelLineageUtils.buildColumnLineageDatasetFacet(openLineageContext, schema))
              .thenReturn(Optional.of(columnLineageDatasetFacet));
          when(ColumnLevelLineageUtils.rewriteOutputDataset(any(), eq(columnLineageDatasetFacet)))
              .thenReturn(outputDataset);

          assertEquals(
              outputDataset, (OpenLineage.OutputDataset) visitor.apply(logicalRelation).get(0));
        }
      }
    }
  }
}
