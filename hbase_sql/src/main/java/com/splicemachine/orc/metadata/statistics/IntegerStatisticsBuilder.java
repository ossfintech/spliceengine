/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.splicemachine.orc.metadata.statistics;

import java.util.List;
import java.util.Optional;

import static com.splicemachine.orc.metadata.statistics.IntegerStatistics.INTEGER_VALUE_BYTES;
import static java.util.Objects.requireNonNull;

public class IntegerStatisticsBuilder
        implements LongValueStatisticsBuilder
{
    private long nonNullValueCount;
    private long minimum = Long.MAX_VALUE;
    private long maximum = Long.MIN_VALUE;


    public static IntegerStatisticsBuilder newBuilder() {
        return new IntegerStatisticsBuilder();
    }

    @Override
    public void addValue(long value)
    {
        nonNullValueCount++;

        minimum = Math.min(value, minimum);
        maximum = Math.max(value, maximum);
    }

    private void addIntegerStatistics(long valueCount, IntegerStatistics value)
    {
        requireNonNull(value, "value is null");
        requireNonNull(value.getMin(), "value.getMin() is null");
        requireNonNull(value.getMax(), "value.getMax() is null");

        nonNullValueCount += valueCount;
        minimum = Math.min(value.getMin(), minimum);
        maximum = Math.max(value.getMax(), maximum);
    }

    private Optional<IntegerStatistics> buildIntegerStatistics()
    {
        if (nonNullValueCount == 0) {
            return Optional.empty();
        }
        return Optional.of(new IntegerStatistics(minimum, maximum));
    }

    @Override
    public ColumnStatistics buildColumnStatistics()
    {
        Optional<IntegerStatistics> integerStatistics = buildIntegerStatistics();
        return new ColumnStatistics(
                nonNullValueCount,
                integerStatistics.map(s -> INTEGER_VALUE_BYTES).orElse(0L),
                null,
                integerStatistics.orElse(null),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Override
    public ColumnStatistics buildColumnPartitionStatistics(String value) {
        addValue(Long.valueOf(value));
        return buildColumnStatistics();
    }

    public static Optional<IntegerStatistics> mergeIntegerStatistics(List<ColumnStatistics> stats)
    {
        IntegerStatisticsBuilder integerStatisticsBuilder = new IntegerStatisticsBuilder();
        for (ColumnStatistics columnStatistics : stats) {
            IntegerStatistics partialStatistics = columnStatistics.getIntegerStatistics();
            if (columnStatistics.getNumberOfValues() > 0) {
                if (partialStatistics == null) {
                    // there are non null values but no statistics, so we can not say anything about the data
                    return Optional.empty();
                }
                integerStatisticsBuilder.addIntegerStatistics(columnStatistics.getNumberOfValues(), partialStatistics);
            }
        }
        return integerStatisticsBuilder.buildIntegerStatistics();
    }
}
