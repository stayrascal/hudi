/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.common.model;

import org.apache.hudi.common.util.Option;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.apache.hudi.avro.HoodieAvroUtils.bytesToAvro;

/**
 * The only difference with {@link OverwriteNonDefaultsWithLatestAvroPayload} is that it supports
 * merging the latest non-null partial fields with the old record instead of replacing the whole record.
 * And merging the non-null fields during preCombine multiple records with same record key instead of choosing the latest record based on ordering field.
 *
 * <p> Regarding #combineAndGetUpdateValue, Assuming a {@link GenericRecord} has row schema: (f0 int , f1 int, f2 int).
 * The first record value is: (1, 2, 3), the second record value is: (4, 5, null) with the field f2 value as null.
 * Calling the #combineAndGetUpdateValue method of the two records returns record: (4, 5, 3).
 * Note that field f2 value is ignored because it is null. </p>
 *
 * <p> Regarding #preCombine, Assuming a {@link GenericRecord} has row schema: (f0 int , f1 int, f2 int, o1 int),
 * and initial two {@link PartialOverwriteWithLatestAvroPayload} with different ordering value.
 * The first record value is (1, null, 1, 1) with the filed f1 value as null, the second value is: (2, 2, null, 2) with the f2 value as null.
 * Calling the #preCombine method of the two records returns record: (2, 2, 1, 2).
 * Note:
 * <ol>
 *   <li>the field f0 value is 2 because the ordering value of second record is bigger.</li>
 *   <li>the filed f1 value is 2 because the f2 value of first record is null.</li>
 *   <li>the filed f2 value is 1 because the f2 value of second record is null.</li>
 *   <li>the filed o1 value is 2 because the ordering value of second record is bigger.</li>
 * </ol>
 *
 * </p>
 */
public class PartialOverwriteWithLatestAvroPayload extends OverwriteWithLatestAvroPayload {

  public PartialOverwriteWithLatestAvroPayload(GenericRecord record, Comparable orderingVal) {
    this(record, orderingVal, null);
  }

  public PartialOverwriteWithLatestAvroPayload(GenericRecord record, Comparable orderingVal, String schema) {
    super(record, orderingVal, schema);
  }

  public PartialOverwriteWithLatestAvroPayload(Option<GenericRecord> record) {
    super(record); // natural order
  }

  @Override
  public Option<IndexedRecord> combineAndGetUpdateValue(IndexedRecord currentValue, Schema schema) throws IOException {
    if (recordBytes.length == 0) {
      return Option.empty();
    }

    GenericRecord incomingRecord = bytesToAvro(recordBytes, schema);
    if (isDeleteRecord(incomingRecord)) {
      return Option.empty();
    }

    GenericRecord currentRecord = (GenericRecord) currentValue;
    List<Schema.Field> fields = schema.getFields();
    fields.forEach(field -> {
      Object value = incomingRecord.get(field.name());
      if (Objects.nonNull(value)) {
        currentRecord.put(field.name(), value);
      }
    });

    return Option.of(currentRecord);
  }

  @Override
  public int compareTo(OverwriteWithLatestAvroPayload oldValue) {
    return oldValue.orderingVal.compareTo(orderingVal);
  }

  @Override
  public OverwriteWithLatestAvroPayload preCombine(OverwriteWithLatestAvroPayload oldValue) {
    if (null == this.schema || null == oldValue.schema) {
      return super.preCombine(oldValue);
    }

    try {
      Schema schema = new Schema.Parser().parse(this.schema);
      Option<IndexedRecord> incomingOption = getInsertValue(new Schema.Parser().parse(this.schema));
      Option<IndexedRecord> insertRecordOption = oldValue.getInsertValue(new Schema.Parser().parse(oldValue.schema));

      if (incomingOption.isPresent() && insertRecordOption.isPresent()) {
        GenericRecord currentRecord = (GenericRecord) incomingOption.get();
        GenericRecord insertRecord = (GenericRecord) insertRecordOption.get();
        boolean chooseCurrent = this.orderingVal.compareTo(oldValue.orderingVal) > 0;

        if (!isDeleteRecord(insertRecord) && !isDeleteRecord(currentRecord)) {
          schema.getFields().forEach(field -> {
            Object insertValue = insertRecord.get(field.name());
            Object currentValue = currentRecord.get(field.name());
            currentRecord.put(field.name(), mergeValue(currentValue, insertValue, chooseCurrent));
          });
          return new PartialOverwriteWithLatestAvroPayload(currentRecord, chooseCurrent ? this.orderingVal : oldValue.orderingVal, this.schema);
        } else {
          return isDeleteRecord(insertRecord) ? this : oldValue;
        }
      } else {
        return insertRecordOption.isPresent() ? oldValue : this;
      }
    } catch (IOException e) {
      return super.preCombine(oldValue);
    }
  }

  private Object mergeValue(Object left, Object right, Boolean chooseLeft) {
    if (null != left && null != right) {
      return chooseLeft ? left : right;
    } else {
      return null == left ? right : left;
    }
  }

}
