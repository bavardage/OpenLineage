package io.openlineage.spark3.agent.lifecycle.plan.columnLineage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.openlineage.spark.agent.util.DatasetIdentifier;
import java.util.List;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.spark.sql.catalyst.expressions.ExprId;
import org.apache.spark.sql.types.IntegerType$;
import org.apache.spark.sql.types.Metadata;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.Test;
import scala.collection.immutable.HashMap;

class ColumnLevelLineageBuilderTest {

  StructType schema =
      new StructType(
          new StructField[] {
            new StructField("a", IntegerType$.MODULE$, false, new Metadata(new HashMap<>())),
            new StructField("b", IntegerType$.MODULE$, false, new Metadata(new HashMap<>()))
          });
  ColumnLevelLineageBuilder builder = new ColumnLevelLineageBuilder(schema);

  ExprId rootExprId = mock(ExprId.class);
  ExprId childExprId = mock(ExprId.class);
  ExprId grandChildExprId1 = mock(ExprId.class);
  ExprId grandChildExprId2 = mock(ExprId.class);

  @Test
  public void testEmptyOutput() {
    assertTrue(builder.getInputsUsedFor("non-existing-output").isEmpty());
  }

  @Test
  public void testSingleInputSingleOutput() {
    DatasetIdentifier di = new DatasetIdentifier("t", "db");
    builder.addOutput(rootExprId, "a");
    builder.addInput(rootExprId, di, "inputA");

    List<Pair<DatasetIdentifier, String>> inputs = builder.getInputsUsedFor("a");

    assertTrue(builder.hasOutputs());
    assertEquals(1, inputs.size());
    assertEquals("inputA", inputs.get(0).getRight());
    assertEquals(di, inputs.get(0).getLeft());
  }

  @Test
  public void testMultipleOutputs() {
    DatasetIdentifier di = new DatasetIdentifier("t", "db");
    builder.addOutput(rootExprId, "a");
    builder.addOutput(rootExprId, "b");
    builder.addInput(rootExprId, di, "inputA");

    assertEquals(1, builder.getInputsUsedFor("a").size());
    assertEquals(1, builder.getInputsUsedFor("b").size());
  }

  @Test
  public void testMultipleInputsAndSingleOutputWithNestedExpressions() {
    DatasetIdentifier di1 = new DatasetIdentifier("t1", "db");
    DatasetIdentifier di2 = new DatasetIdentifier("t2", "db");

    builder.addOutput(rootExprId, "a");
    builder.addDependency(rootExprId, childExprId);
    builder.addDependency(childExprId, grandChildExprId1);
    builder.addDependency(childExprId, grandChildExprId2);
    builder.addInput(grandChildExprId1, di1, "input1");
    builder.addInput(grandChildExprId1, di2, "input2");

    List<Pair<DatasetIdentifier, String>> inputs = builder.getInputsUsedFor("a");

    assertTrue(builder.hasOutputs());
    assertEquals(2, inputs.size());
    assertEquals("input1", inputs.get(0).getRight());
    assertEquals("input2", inputs.get(1).getRight());
    assertEquals(di1, inputs.get(0).getLeft());
    assertEquals(di2, inputs.get(1).getLeft());
  }

  @Test
  public void testCycledExpressionDependency() {
    builder.addOutput(rootExprId, "a");
    builder.addDependency(rootExprId, childExprId);
    builder.addDependency(childExprId, rootExprId); // cycle that should not happen

    List<Pair<DatasetIdentifier, String>> inputs = builder.getInputsUsedFor("a");
    assertEquals(0, inputs.size());
  }
}
