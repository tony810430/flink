/*
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

package org.apache.flink.table.runtime.operators.join.temporal;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.operators.InternalTimer;
import org.apache.flink.streaming.api.operators.InternalTimerService;
import org.apache.flink.streaming.api.operators.TimestampedCollector;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.util.RowDataUtil;
import org.apache.flink.table.data.utils.JoinedRowData;
import org.apache.flink.table.runtime.generated.GeneratedJoinCondition;
import org.apache.flink.table.runtime.generated.JoinCondition;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The operator for temporal join (FOR SYSTEM_TIME AS OF o.rowtime) on row time, it has no
 * limitation about message types of the left input and right input, this means the operator deals
 * changelog well.
 *
 * <p>For Event-time temporal join, its probe side is a regular table, its build side is a versioned
 * table, the version of versioned table can extract from the build side state. This operator works
 * by keeping on the state collection of probe and build records to process on next watermark. The
 * idea is that between watermarks we are collecting those elements and once we are sure that there
 * will be no updates we emit the correct result and clean up the expired data in state.
 *
 * <p>Cleaning up the state drops all of the "old" values from the probe side, where "old" is
 * defined as older then the current watermark. Build side is also cleaned up in the similar
 * fashion, however we always keep at least one record - the latest one - even if it's past the last
 * watermark.
 *
 * <p>One more trick is how the emitting results and cleaning up is triggered. It is achieved by
 * registering timers for the keys. We could register a timer for every probe and build side
 * element's event time (when watermark exceeds this timer, that's when we are emitting and/or
 * cleaning up the state). However this would cause huge number of registered timers. For example
 * with following evenTimes of probe records accumulated: {1, 2, 5, 8, 9}, if we had received
 * Watermark(10), it would trigger 5 separate timers for the same key. To avoid that we always keep
 * only one single registered timer for any given key, registered for the minimal value. Upon
 * triggering it, we process all records with event times older then or equal to currentWatermark.
 */
public class TemporalRowTimeJoinOperator extends BaseTwoInputStreamOperatorWithStateRetention {

    private static final long serialVersionUID = 6642514795175288193L;

    private static final String NEXT_LEFT_INDEX_STATE_NAME = "next-index";
    private static final String LEFT_STATE_NAME = "left";
    private static final String LAST_INDEX_STATE_NAME = "last-index";
    private static final String PREV_INDEX_STATE_NAME = "prev-index";
    private static final String NEXT_ROW_TIME_HEAP_INDEX_STATE_NAME = "next-row-time-heap-index";
    private static final String ROW_TIME_HEAP_STATE_NAME = "row-time-heap";
    private static final String RIGHT_STATE_NAME = "right";
    private static final String REGISTERED_TIMER_STATE_NAME = "timer";
    private static final String TIMERS_STATE_NAME = "timers";

    private final boolean isLeftOuterJoin;
    private final InternalTypeInfo<RowData> leftType;
    private final InternalTypeInfo<RowData> rightType;
    private final GeneratedJoinCondition generatedJoinCondition;
    private final int leftTimeAttribute;
    private final int rightTimeAttribute;

    private final RowtimeComparator rightRowtimeComparator;

    private transient LeftState leftState;

    /**
     * Mapping from timestamp to right side `Row`.
     *
     * <p>TODO: having `rightState` as an OrderedMapState would allow us to avoid sorting cost once
     * per watermark
     */
    private transient MapState<Long, RowData> rightState;

    // Long for correct handling of default null
    private transient ValueState<Long> registeredTimer;
    private transient TimestampedCollector<RowData> collector;
    private transient InternalTimerService<VoidNamespace> timerService;

    private transient JoinCondition joinCondition;
    private transient JoinedRowData outRow;
    private transient GenericRowData rightNullRow;

    public TemporalRowTimeJoinOperator(
            InternalTypeInfo<RowData> leftType,
            InternalTypeInfo<RowData> rightType,
            GeneratedJoinCondition generatedJoinCondition,
            int leftTimeAttribute,
            int rightTimeAttribute,
            long minRetentionTime,
            long maxRetentionTime,
            boolean isLeftOuterJoin) {
        super(minRetentionTime, maxRetentionTime);
        this.leftType = leftType;
        this.rightType = rightType;
        this.generatedJoinCondition = generatedJoinCondition;
        this.leftTimeAttribute = leftTimeAttribute;
        this.rightTimeAttribute = rightTimeAttribute;
        this.rightRowtimeComparator = new RowtimeComparator(rightTimeAttribute);
        this.isLeftOuterJoin = isLeftOuterJoin;
    }

    @Override
    public void open() throws Exception {
        super.open();
        joinCondition =
                generatedJoinCondition.newInstance(getRuntimeContext().getUserCodeClassLoader());
        joinCondition.setRuntimeContext(getRuntimeContext());
        joinCondition.open(new Configuration());

        rightState =
                getRuntimeContext()
                        .getMapState(
                                new MapStateDescriptor<>(RIGHT_STATE_NAME, Types.LONG, rightType));
        registeredTimer =
                getRuntimeContext()
                        .getState(
                                new ValueStateDescriptor<>(
                                        REGISTERED_TIMER_STATE_NAME, Types.LONG));

        leftState = new LeftState();

        timerService =
                getInternalTimerService(TIMERS_STATE_NAME, VoidNamespaceSerializer.INSTANCE, this);

        outRow = new JoinedRowData();
        rightNullRow = new GenericRowData(rightType.toRowType().getFieldCount());
        collector = new TimestampedCollector<>(output);
    }

    @Override
    public void processElement1(StreamRecord<RowData> element) throws Exception {
        RowData row = element.getValue();

        long rowTime = getLeftTime(row);
        leftState.put(rowTime, row);
        registerSmallestTimer(rowTime); // Timer to emit and clean up the state

        registerProcessingCleanupTimer();
    }

    @Override
    public void processElement2(StreamRecord<RowData> element) throws Exception {
        RowData row = element.getValue();

        long rowTime = getRightTime(row);
        rightState.put(rowTime, row);
        registerSmallestTimer(rowTime); // Timer to clean up the state

        registerProcessingCleanupTimer();
    }

    @Override
    public void onEventTime(InternalTimer<Object, VoidNamespace> timer) throws Exception {
        registeredTimer.clear();
        long lastUnprocessedTime = emitResultAndCleanUpState(timerService.currentWatermark());
        if (lastUnprocessedTime < Long.MAX_VALUE) {
            registerTimer(lastUnprocessedTime);
        }

        // if we have more state at any side, then update the timer, else clean it up.
        if (stateCleaningEnabled) {
            if (lastUnprocessedTime < Long.MAX_VALUE || !rightState.isEmpty()) {
                registerProcessingCleanupTimer();
            } else {
                cleanupLastTimer();
                leftState.clear();
            }
        }
    }

    @Override
    public void close() throws Exception {
        if (joinCondition != null) {
            joinCondition.close();
        }
    }

    /**
     * @return a row time of the oldest unprocessed probe record or Long.MaxValue, if all records
     *     have been processed.
     */
    private long emitResultAndCleanUpState(long currentWatermark) throws Exception {
        List<RowData> rightRowsSorted = getRightRowSorted(rightRowtimeComparator);
        long lastUnprocessedTime = Long.MAX_VALUE;

        // the output records' order should keep same with left input records arrival order
        final Map<Long, RowData> orderedLeftRecords = new TreeMap<>();

        while (leftState.findEarliestRowTime() != null &&
                leftState.findEarliestRowTime() <= currentWatermark) {
            leftState.extractEarliestRows().forEach(orderedLeftRecords::put);
        }
        if (leftState.findEarliestRowTime() != null) {
            lastUnprocessedTime = leftState.findEarliestRowTime();
        }

        // iterate the triggered left records in the ascending order of the sequence key, i.e. the
        // arrival order.
        orderedLeftRecords.forEach(
                (leftSeq, leftRow) -> {
                    long leftTime = getLeftTime(leftRow);
                    Optional<RowData> rightRow = latestRightRowToJoin(rightRowsSorted, leftTime);
                    if (rightRow.isPresent() && RowDataUtil.isAccumulateMsg(rightRow.get())) {
                        if (joinCondition.apply(leftRow, rightRow.get())) {
                            collectJoinedRow(leftRow, rightRow.get());
                        } else {
                            if (isLeftOuterJoin) {
                                collectJoinedRow(leftRow, rightNullRow);
                            }
                        }
                    } else {
                        if (isLeftOuterJoin) {
                            collectJoinedRow(leftRow, rightNullRow);
                        }
                    }
                });
        orderedLeftRecords.clear();

        cleanupExpiredVersionInState(currentWatermark, rightRowsSorted);
        return lastUnprocessedTime;
    }

    private void collectJoinedRow(RowData leftSideRow, RowData rightRow) {
        outRow.setRowKind(leftSideRow.getRowKind());
        outRow.replace(leftSideRow, rightRow);
        collector.collect(outRow);
    }

    /**
     * Removes all expired version in the versioned table's state according to current watermark.
     */
    private void cleanupExpiredVersionInState(long currentWatermark, List<RowData> rightRowsSorted)
            throws Exception {
        int i = 0;
        int indexToKeep = firstIndexToKeep(currentWatermark, rightRowsSorted);
        // clean old version data that behind current watermark
        while (i < indexToKeep) {
            long rightTime = getRightTime(rightRowsSorted.get(i));
            rightState.remove(rightTime);
            i += 1;
        }
    }

    /**
     * The method to be called when a cleanup timer fires.
     *
     * @param time The timestamp of the fired timer.
     */
    @Override
    public void cleanupState(long time) {
        leftState.clear();
        rightState.clear();
        registeredTimer.clear();
    }

    private int firstIndexToKeep(long timerTimestamp, List<RowData> rightRowsSorted) {
        int firstIndexNewerThenTimer =
                indexOfFirstElementNewerThanTimer(timerTimestamp, rightRowsSorted);

        if (firstIndexNewerThenTimer < 0) {
            return rightRowsSorted.size() - 1;
        } else {
            return firstIndexNewerThenTimer - 1;
        }
    }

    private int indexOfFirstElementNewerThanTimer(long timerTimestamp, List<RowData> list) {
        ListIterator<RowData> iter = list.listIterator();
        while (iter.hasNext()) {
            if (getRightTime(iter.next()) > timerTimestamp) {
                return iter.previousIndex();
            }
        }
        return -1;
    }

    /**
     * Binary search {@code rightRowsSorted} to find the latest right row to join with {@code
     * leftTime}. Latest means a right row with largest time that is still smaller or equal to
     * {@code leftTime}. For example with: rightState = [1(+I), 4(+U), 7(+U), 9(-D), 12(I)],
     *
     * <p>If left time is 6, the valid period should be [4, 7), data 4(+U) should be joined.
     *
     * <p>If left time is 10, the valid period should be [9, 12), but data 9(-D) is a DELETE message
     * which means the the correspond version has no data in period [9, 12), data 9(-D) should not
     * be correlated.
     *
     * @return found element or {@code Optional.empty} If such row was not found (either {@code
     *     rightRowsSorted} is empty or all {@code rightRowsSorted} are are newer).
     */
    private Optional<RowData> latestRightRowToJoin(List<RowData> rightRowsSorted, long leftTime) {
        return latestRightRowToJoin(rightRowsSorted, 0, rightRowsSorted.size() - 1, leftTime);
    }

    private Optional<RowData> latestRightRowToJoin(
            List<RowData> rightRowsSorted, int low, int high, long leftTime) {
        if (low > high) {
            // exact value not found, we are returning largest from the values smaller then leftTime
            if (low - 1 < 0) {
                return Optional.empty();
            } else {
                return Optional.of(rightRowsSorted.get(low - 1));
            }
        } else {
            int mid = (low + high) >>> 1;
            RowData midRow = rightRowsSorted.get(mid);
            long midTime = getRightTime(midRow);
            int cmp = Long.compare(midTime, leftTime);
            if (cmp < 0) {
                return latestRightRowToJoin(rightRowsSorted, mid + 1, high, leftTime);
            } else if (cmp > 0) {
                return latestRightRowToJoin(rightRowsSorted, low, mid - 1, leftTime);
            } else {
                return Optional.of(midRow);
            }
        }
    }

    private void registerSmallestTimer(long timestamp) throws IOException {
        Long currentRegisteredTimer = registeredTimer.value();
        if (currentRegisteredTimer == null) {
            registerTimer(timestamp);
        } else if (currentRegisteredTimer > timestamp) {
            timerService.deleteEventTimeTimer(VoidNamespace.INSTANCE, currentRegisteredTimer);
            registerTimer(timestamp);
        }
    }

    private void registerTimer(long timestamp) throws IOException {
        registeredTimer.update(timestamp);
        timerService.registerEventTimeTimer(VoidNamespace.INSTANCE, timestamp);
    }

    private List<RowData> getRightRowSorted(RowtimeComparator rowtimeComparator) throws Exception {
        List<RowData> rightRows = new ArrayList<>();
        for (RowData row : rightState.values()) {
            rightRows.add(row);
        }
        rightRows.sort(rowtimeComparator);
        return rightRows;
    }

    private long getLeftTime(RowData leftRow) {
        return leftRow.getLong(leftTimeAttribute);
    }

    private long getRightTime(RowData rightRow) {
        return rightRow.getLong(rightTimeAttribute);
    }

    // ------------------------------------------------------------------------------------------

    private static class RowtimeComparator implements Comparator<RowData>, Serializable {

        private static final long serialVersionUID = 8160134014590716914L;

        private final int timeAttribute;

        private RowtimeComparator(int timeAttribute) {
            this.timeAttribute = timeAttribute;
        }

        @Override
        public int compare(RowData o1, RowData o2) {
            long o1Time = o1.getLong(timeAttribute);
            long o2Time = o2.getLong(timeAttribute);
            return Long.compare(o1Time, o2Time);
        }
    }

    @VisibleForTesting
    static String getNextLeftIndexStateName() {
        return NEXT_LEFT_INDEX_STATE_NAME;
    }

    @VisibleForTesting
    static String getRegisteredTimerStateName() {
        return REGISTERED_TIMER_STATE_NAME;
    }

    @VisibleForTesting
    static String getNextRowTimeHeapIndexStateName() {
        return NEXT_ROW_TIME_HEAP_INDEX_STATE_NAME;
    }

    private class LeftState {
        /** Incremental index generator for {@link #leftState}'s keys. */
        private final transient ValueState<Long> nextLeftIndex;

        /**
         * Mapping from artificial row index (generated by `nextLeftIndex`) into the left side `Row`. We
         * can not use List to accumulate Rows, because we need efficient deletes of the oldest rows.
         */
        private final transient MapState<Long, RowData> leftState;

        /**
         * Mapping to the latest inserted row of all rows with the same row time in {@link #leftState}.
         */
        private final transient MapState<Long, Long> lastIndexState;

        /**
         * Mapping from the index to its previous index, that both index point to the value of
         * {@link #leftState} that contains the same row time.
         */
        private final transient MapState<Long, Long> prevIndexState;

        /** Incremental index generator for {@link #rowTimeHeapState}'s keys. */
        private final transient ValueState<Long> nextRowTimeHeapIndexState;

        /**
         * MinHeap structure to store row time.
         */
        private final transient MapState<Long, Long> rowTimeHeapState;

        LeftState() {
            nextLeftIndex =
                    getRuntimeContext()
                            .getState(
                                    new ValueStateDescriptor<>(NEXT_LEFT_INDEX_STATE_NAME, Types.LONG));
            leftState =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>(LEFT_STATE_NAME, Types.LONG, leftType));
            lastIndexState =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>(LAST_INDEX_STATE_NAME, Types.LONG, Types.LONG));
            prevIndexState =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>(PREV_INDEX_STATE_NAME, Types.LONG, Types.LONG));
            nextRowTimeHeapIndexState =
                    getRuntimeContext()
                            .getState(
                                    new ValueStateDescriptor<>(NEXT_ROW_TIME_HEAP_INDEX_STATE_NAME, Types.LONG));
            rowTimeHeapState =
                    getRuntimeContext()
                            .getMapState(
                                    new MapStateDescriptor<>(ROW_TIME_HEAP_STATE_NAME, Types.LONG, Types.LONG));
        }

        public RowData put(Long rowTime, RowData row) throws Exception {
            if (!lastIndexState.contains(rowTime)) {
                insertRowTime(rowTime);
            }
            Long index = getNextLeftIndex();
            leftState.put(index, row);
            prevIndexState.put(index, lastIndexState.get(rowTime));
            lastIndexState.put(rowTime, index);
            return row;
        }

        public Map<Long, RowData> extractEarliestRows() throws Exception {
            Map<Long, RowData> result = new HashMap<>();
            Long key = extractEarliestRowTime();

            Long index = lastIndexState.get(key);
            while (index != null) {
                result.put(index, leftState.get(index));
                index = prevIndexState.get(index);
            }

            remove(key);
            return result;
        }

        public void clear() {
            nextLeftIndex.clear();
            leftState.clear();
            lastIndexState.clear();
            prevIndexState.clear();
            nextRowTimeHeapIndexState.clear();
            rowTimeHeapState.clear();
        }

        private void remove(Long rowTime) throws Exception {
            Long index = lastIndexState.get(rowTime);
            Long nextIndex;
            while (index != null) {
                leftState.remove(index);
                nextIndex = prevIndexState.get(index);
                prevIndexState.remove(index);
                index = nextIndex;
            }
            lastIndexState.remove(rowTime);
        }

        private long getNextLeftIndex() throws IOException {
            Long index = nextLeftIndex.value();
            if (index == null) {
                index = 0L;
            }
            nextLeftIndex.update(index + 1);
            return index;
        }

        // min heap's find minimum operator
        public Long findEarliestRowTime() throws Exception {
            return rowTimeHeapState.get(1L);
        }

        // min heap's extract minimum operator
        private Long extractEarliestRowTime() throws Exception {
            Long earliestRowTime = rowTimeHeapState.get(1L);
            Long index = nextRowTimeHeapIndexState.value();

            if (index != null && index <= 2L) {
                rowTimeHeapState.clear();
                nextRowTimeHeapIndexState.clear();
            } else if (index != null) {
                rowTimeHeapState.put(1L, rowTimeHeapState.get(index - 1L));
                rowTimeHeapState.remove(index - 1L);
                nextRowTimeHeapIndexState.update(index - 1L);

                index = 1L;
                Long current = rowTimeHeapState.get(index);
                Long leftChild = rowTimeHeapState.get(index * 2L);
                Long rightChild = rowTimeHeapState.get(index * 2L + 1L);

                while ((leftChild != null && current > leftChild) ||
                        (rightChild != null && current > rightChild)) {
                    if (leftChild != null && rightChild != null && leftChild < rightChild) {
                        rowTimeHeapState.put(index, leftChild);
                        index = index * 2L;
                    } else {
                        // in heap structure, it won't happen that left child is not exist,
                        // but right child is exist.
                        rowTimeHeapState.put(index, rightChild);
                        index = index * 2L + 1L;
                    }
                    rowTimeHeapState.put(index, current);
                    leftChild = rowTimeHeapState.get(index * 2L);
                    rightChild = rowTimeHeapState.get(index * 2L + 1L);
                }
            }

            return earliestRowTime;
        }

        // min heap's insert operator
        private void insertRowTime(Long rowTime) throws Exception {
            long index = getNextRowTimeHeapIndex();
            rowTimeHeapState.put(index, rowTime);

            Long parentRowTime = rowTimeHeapState.get(index / 2L);
            while (index > 1L && rowTime < parentRowTime) {
                rowTimeHeapState.put(index, parentRowTime);
                index /= 2L;
                rowTimeHeapState.put(index, rowTime);
                parentRowTime = rowTimeHeapState.get(index / 2L);
            }
        }

        private long getNextRowTimeHeapIndex() throws IOException {
            Long index = nextRowTimeHeapIndexState.value();
            if (index == null) {
                index = 1L;
            }
            nextRowTimeHeapIndexState.update(index + 1L);
            return index;
        }
    }
}
