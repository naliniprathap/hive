/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates.gen;

import java.util.ArrayList;

import org.apache.hadoop.hive.ql.exec.Description;
import org.apache.hadoop.hive.ql.exec.vector.expressions.VectorExpression;
import org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates.VectorAggregateExpression;
import org.apache.hadoop.hive.ql.exec.vector.expressions.aggregates.
    VectorAggregateExpression.AggregationBuffer;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.LongObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;

/**
* VectorUDAFAvgDouble. Vectorized implementation for AVG aggregates. 
*/
@Description(name = "avg", value = "_FUNC_(expr) - Returns the average value of expr (vectorized, type: double)")
public class VectorUDAFAvgDouble extends VectorAggregateExpression {
    
    /** 
    /* class for storing the current aggregate value.
    */
    static private final class Aggregation implements AggregationBuffer {
      double sum;
      long count;
      boolean isNull;
    }
    
    private VectorExpression inputExpression;
    private Object[] partialResult;
    private LongWritable resultCount;
    private DoubleWritable resultSum;
    private StructObjectInspector soi;
        
    public VectorUDAFAvgDouble(VectorExpression inputExpression) {
      super();
      this.inputExpression = inputExpression;
      partialResult = new Object[2];
      partialResult[0] = resultCount = new LongWritable();
      partialResult[1] = resultSum = new DoubleWritable();
      
      initPartialResultInspector();
    }

  private void initPartialResultInspector () {
      ArrayList<ObjectInspector> foi = new ArrayList<ObjectInspector>();
        foi.add(PrimitiveObjectInspectorFactory.writableLongObjectInspector);
        foi.add(PrimitiveObjectInspectorFactory.writableDoubleObjectInspector);
        ArrayList<String> fname = new ArrayList<String>();
        fname.add("count");
        fname.add("sum");
        soi = ObjectInspectorFactory.getStandardStructObjectInspector(fname, foi);
    }
    
    
    @Override
    public void aggregateInput(AggregationBuffer agg, VectorizedRowBatch unit) 
    throws HiveException {
      
      inputExpression.evaluate(unit);
      
      DoubleColumnVector inputVector = (DoubleColumnVector)unit.
      cols[this.inputExpression.getOutputColumn()];
      
      int batchSize = unit.size;
      
      if (batchSize == 0) {
        return;
      }
      
      Aggregation myagg = (Aggregation)agg;

      double[] vector = inputVector.vector;
      
      if (inputVector.isRepeating) {
        if (inputVector.noNulls || !inputVector.isNull[0]) {
          if (myagg.isNull) {
            myagg.isNull = false;
            myagg.sum = 0;
            myagg.count = 0;
          }
          myagg.sum += vector[0]*batchSize;
          myagg.count += batchSize;
        }
        return;
      }
      
      if (!unit.selectedInUse && inputVector.noNulls) {
        iterateNoSelectionNoNulls(myagg, vector, batchSize);
      }
      else if (!unit.selectedInUse) {
        iterateNoSelectionHasNulls(myagg, vector, batchSize, inputVector.isNull);
      }
      else if (inputVector.noNulls){
        iterateSelectionNoNulls(myagg, vector, batchSize, unit.selected);
      }
      else {
        iterateSelectionHasNulls(myagg, vector, batchSize, inputVector.isNull, unit.selected);
      }
    }
  
    private void iterateSelectionHasNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize,
        boolean[] isNull, 
        int[] selected) {
      
      for (int j=0; j< batchSize; ++j) {
        int i = selected[j];
        if (!isNull[i]) {
          double value = vector[i];
          if (myagg.isNull) {
            myagg.isNull = false;
            myagg.sum = 0;
            myagg.count = 0;
          }
          myagg.sum += value;
          myagg.count += 1;
        }
      }
    }

    private void iterateSelectionNoNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize, 
        int[] selected) {
      
      if (myagg.isNull) {
        myagg.isNull = false;
        myagg.sum = 0;
        myagg.count = 0;
      }
      
      for (int i=0; i< batchSize; ++i) {
        double value = vector[selected[i]];
        myagg.sum += value;
        myagg.count += 1;
      }
    }

    private void iterateNoSelectionHasNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize,
        boolean[] isNull) {
      
      for(int i=0;i<batchSize;++i) {
        if (!isNull[i]) {
          double value = vector[i];
          if (myagg.isNull) { 
            myagg.isNull = false;
            myagg.sum = 0;
            myagg.count = 0;
          }
          myagg.sum += value;
          myagg.count += 1;
        }
      }
    }

    private void iterateNoSelectionNoNulls(
        Aggregation myagg, 
        double[] vector, 
        int batchSize) {
      if (myagg.isNull) {
        myagg.isNull = false;
        myagg.sum = 0;
        myagg.count = 0;
      }
      
      for (int i=0;i<batchSize;++i) {
        double value = vector[i];
        myagg.sum += value;
        myagg.count += 1;
      }
    }

    @Override
    public AggregationBuffer getNewAggregationBuffer() throws HiveException {
      return new Aggregation();
    }

    @Override
    public void reset(AggregationBuffer agg) throws HiveException {
      Aggregation myAgg = (Aggregation) agg;
      myAgg.isNull = true;
    }

    @Override
    public Object evaluateOutput(
        AggregationBuffer agg) throws HiveException {
      Aggregation myagg = (Aggregation) agg;
      if (myagg.isNull) {
        return null;
      }
      else {
        assert(0 < myagg.count);
        resultCount.set (myagg.count);
        resultSum.set (myagg.sum);
        return partialResult;
      }
    }
    
  @Override
    public ObjectInspector getOutputObjectInspector() {
      return soi;
    }
}
