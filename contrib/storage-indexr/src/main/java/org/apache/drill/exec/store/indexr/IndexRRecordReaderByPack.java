/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.indexr;

import org.apache.commons.io.IOUtils;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.exec.ops.OperatorContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.vector.BigIntVector;
import org.apache.drill.exec.vector.Float4Vector;
import org.apache.drill.exec.vector.Float8Vector;
import org.apache.drill.exec.vector.IntVector;
import org.apache.drill.exec.vector.VarCharVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.indexr.data.BytePiece;
import io.indexr.segment.ColumnType;
import io.indexr.segment.DPValues;
import io.indexr.segment.Segment;
import io.indexr.segment.SegmentSchema;
import io.indexr.segment.helper.SegmentOpener;
import io.indexr.segment.helper.SingleWork;
import io.indexr.segment.pack.DataPack;
import io.indexr.util.MemoryUtil;

public class IndexRRecordReaderByPack extends IndexRRecordReader {
  private static final Logger log = LoggerFactory.getLogger(IndexRRecordReaderByPack.class);

  private SegmentOpener segmentOpener;
  private List<SingleWork> works;
  private int curStepId = 0;

  private Map<String, Segment> segmentMap;

  public IndexRRecordReaderByPack(String tableName,//
                                  SegmentSchema schema,//
                                  List<SchemaPath> projectColumns,//
                                  SegmentOpener segmentOpener,//
                                  List<SingleWork> works) {
    super(tableName, schema, projectColumns);
    this.segmentOpener = segmentOpener;
    this.works = works;
  }

  @Override
  public void setup(OperatorContext context, OutputMutator output) throws ExecutionSetupException {
    super.setup(context, output);
    this.segmentMap = new HashMap<>();
    try {
      for (SingleWork work : works) {
        if (!segmentMap.containsKey(work.segment())) {
          segmentMap.put(work.segment(), segmentOpener.open(work.segment()));
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int next() {
    try {
      if (curStepId >= works.size()) {
        return 0;
      }

      for (ProjectedColumnInfo info : projectedColumnInfos) {
        info.valueVector.setInitialCapacity(DataPack.MAX_COUNT);
      }
      SingleWork stepWork = works.get(curStepId);
      curStepId++;

      Segment segment = segmentMap.get(stepWork.segment());
      int read = read(segment, stepWork.packId(), 0);
      for (ProjectedColumnInfo info : projectedColumnInfos) {
        info.valueVector.getMutator().setValueCount(read);
      }

      return read;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private int read(Segment segment, int packId, int preRowCount) throws Exception {
    int read = -1;
    for (ProjectedColumnInfo info : projectedColumnInfos) {
      Integer columnId = DrillIndexRTable.mapColumn(info.columnSchema, segment.schema());
      if (columnId == null) {
        throw new RuntimeException(String.format("column %s not found in %s", info.columnSchema, segment.schema()));
      }
      DPValues dataPack = segment.column(columnId).pack(packId);

      // Stupid code.
      int fromRowId = 0;
      int count = dataPack.count();
      read = count;
      int realOffset = preRowCount - fromRowId;

      switch (info.columnSchema.dataType) {
        case ColumnType.INT: {
          IntVector.Mutator mutator = (IntVector.Mutator) info.valueVector.getMutator();
          dataPack.foreach(fromRowId, count, (int id, int v) -> mutator.setSafe(realOffset + id, v));
          break;
        }
        case ColumnType.LONG: {
          BigIntVector.Mutator mutator = (BigIntVector.Mutator) info.valueVector.getMutator();
          dataPack.foreach(fromRowId, count, (int id, long v) -> mutator.setSafe(realOffset + id, v));
          break;
        }
        case ColumnType.FLOAT: {
          Float4Vector.Mutator mutator = (Float4Vector.Mutator) info.valueVector.getMutator();
          dataPack.foreach(fromRowId, count, (int id, float v) -> mutator.setSafe(realOffset + id, v));
          break;
        }
        case ColumnType.DOUBLE: {
          Float8Vector.Mutator mutator = (Float8Vector.Mutator) info.valueVector.getMutator();
          dataPack.foreach(fromRowId, count, (int id, double v) -> mutator.setSafe(realOffset + id, v));
          break;
        }
        case ColumnType.STRING: {
          ByteBuffer byteBuffer = MemoryUtil.getHollowDirectByteBuffer();
          VarCharVector.Mutator mutator = (VarCharVector.Mutator) info.valueVector.getMutator();
          dataPack.foreach(fromRowId, count, (int id, BytePiece bytes) -> {
            assert bytes.base == null;
            MemoryUtil.setByteBuffer(byteBuffer, bytes.addr, bytes.len, null);
            mutator.setSafe(realOffset + id, byteBuffer, 0, byteBuffer.remaining());
          });
          break;
        }
        default:
          throw new IllegalStateException(String.format("Unhandled date type %s", info.columnSchema.dataType));
      }
    }
    return read;
  }

  @Override
  public void close() throws Exception {
    if (segmentMap != null) {
      segmentMap.values().forEach(IOUtils::closeQuietly);
      segmentMap = null;
      segmentOpener = null;
      works = null;
    }
  }
}