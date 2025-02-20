/*
 *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.apache.paimon.benchmark;

import org.apache.paimon.CoreOptions;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.options.Options;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.Table;
import org.apache.paimon.table.sink.CommitMessage;
import org.apache.paimon.table.sink.StreamTableCommit;
import org.apache.paimon.table.sink.StreamTableWrite;
import org.apache.paimon.table.sink.StreamWriteBuilder;
import org.apache.paimon.table.source.Split;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Benchmark for table read. */
public class TableReadBenchmark extends TableBenchmark {

    private final int rowCount = 1000000;

    @Test
    public void testRead() throws Exception {
        Map<String, Table> tables = new LinkedHashMap<>();
        tables.put("orc", prepareData(orc(), "orc"));
        tables.put("parquet", prepareData(parquet(), "parquet"));
        tables.put("avro", prepareData(avro(), "avro"));

        innerTest(tables);
        /*
         * OpenJDK 64-Bit Server VM 1.8.0_292-b10 on Mac OS X 10.16
         * Apple M1 Pro
         * read:                            Best/Avg Time(ms)    Row Rate(K/s)      Per Row(ns)   Relative
         * ------------------------------------------------------------------------------------------------
         * OPERATORTEST_read_read-orc            1046 / 1295           2867.3            348.8       1.0X
         * OPERATORTEST_read_read-parquet        3076 / 5295            975.4           1025.2       0.3X
         * OPERATORTEST_read_read-avro           4156 / 4362            721.8           1385.5       0.3X
         */
    }

    private Options orc() {
        Options options = new Options();
        options.set(CoreOptions.FILE_FORMAT, CoreOptions.FileFormatType.ORC);
        return options;
    }

    private Options parquet() {
        Options options = new Options();
        options.set(CoreOptions.FILE_FORMAT, CoreOptions.FileFormatType.PARQUET);
        return options;
    }

    private Options avro() {
        Options options = new Options();
        options.set(CoreOptions.FILE_FORMAT, CoreOptions.FileFormatType.AVRO);
        return options;
    }

    private void innerTest(Map<String, Table> tables) {
        int readTime = 3;
        Benchmark benchmark =
                new Benchmark("read", readTime * rowCount)
                        .setNumWarmupIters(1)
                        .setOutputPerIteration(true);

        for (String name : tables.keySet()) {
            benchmark.addCase(
                    "read-" + name,
                    5,
                    () -> {
                        Table table = tables.get(name);
                        for (int i = 0; i < readTime; i++) {
                            List<Split> splits = table.newReadBuilder().newScan().plan().splits();
                            AtomicLong readCount = new AtomicLong(0);
                            try {
                                for (Split split : splits) {
                                    RecordReader<InternalRow> reader =
                                            table.newReadBuilder().newRead().createReader(split);
                                    reader.forEachRemaining(row -> readCount.incrementAndGet());
                                }
                                System.out.printf("Finish read %d rows.\n", readCount.get());
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
        }
        benchmark.run();
    }

    private Table prepareData(Options options, String tableName) throws Exception {
        Table table = createTable(options, tableName);
        StreamWriteBuilder writeBuilder = table.newStreamWriteBuilder();
        StreamTableWrite write = writeBuilder.newWrite();
        StreamTableCommit commit = writeBuilder.newCommit();
        AtomicInteger writeCount = new AtomicInteger(0);
        for (int i = 0; i < rowCount; i++) {
            try {
                write.write(newRandomRow());
                writeCount.incrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        List<CommitMessage> commitMessages = write.prepareCommit(true, 1);
        commit.commit(1, commitMessages);

        write.close();
        return table;
    }
}
