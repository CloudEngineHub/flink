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

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.eventtime.IndexedCombinedWatermarkStatus;
import org.apache.flink.api.common.operators.MailboxExecutor;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.event.WatermarkEvent;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.groups.InternalOperatorMetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.StreamOperatorStateHandler.CheckpointedStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.RecordAttributes;
import org.apache.flink.streaming.runtime.streamrecord.RecordAttributesBuilder;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.watermarkstatus.WatermarkStatus;
import org.apache.flink.streaming.util.LatencyStats;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Base class for all stream operators. Operators that contain a user function should extend the
 * class {@link AbstractUdfStreamOperator} instead (which is a specialized subclass of this class).
 *
 * <p>For concrete implementations, one of the following two interfaces must also be implemented, to
 * mark the operator as unary or binary: {@link OneInputStreamOperator} or {@link
 * TwoInputStreamOperator}.
 *
 * <p>Methods of {@code StreamOperator} are guaranteed not to be called concurrently. Also, if using
 * the timer service, timer callbacks are also guaranteed not to be called concurrently with methods
 * on {@code StreamOperator}.
 *
 * <p>Note, this class is going to be removed and replaced in the future by {@link
 * AbstractStreamOperatorV2}. However as {@link AbstractStreamOperatorV2} is currently experimental,
 * {@link AbstractStreamOperator} has not been deprecated just yet.
 *
 * @param <OUT> The output type of the operator.
 */
@PublicEvolving
public abstract class AbstractStreamOperator<OUT>
        implements StreamOperator<OUT>,
                YieldingOperator<OUT>,
                CheckpointedStreamOperator,
                KeyContextHandler,
                Serializable {
    private static final long serialVersionUID = 1L;

    /** The logger used by the operator class and its subclasses. */
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractStreamOperator.class);

    // ---------------- runtime fields ------------------

    /** The task that contains this operator (and other operators in the same chain). */
    private transient StreamTask<?, ?> container;

    protected transient StreamConfig config;

    protected transient Output<StreamRecord<OUT>> output;

    protected transient IndexedCombinedWatermarkStatus combinedWatermark;

    /** The runtime context for UDFs. */
    private transient StreamingRuntimeContext runtimeContext;

    private transient @Nullable MailboxExecutor mailboxExecutor;

    private transient @Nullable MailboxWatermarkProcessor watermarkProcessor;

    // ---------------- key/value state ------------------

    /**
     * {@code KeySelector} for extracting a key from an element being processed. This is used to
     * scope keyed state to a key. This is null if the operator is not a keyed operator.
     *
     * <p>This is for elements from the first input.
     */
    protected transient KeySelector<?, ?> stateKeySelector1;

    /**
     * {@code KeySelector} for extracting a key from an element being processed. This is used to
     * scope keyed state to a key. This is null if the operator is not a keyed operator.
     *
     * <p>This is for elements from the second input.
     */
    protected transient KeySelector<?, ?> stateKeySelector2;

    protected transient StreamOperatorStateHandler stateHandler;

    protected transient InternalTimeServiceManager<?> timeServiceManager;

    // --------------- Metrics ---------------------------

    /** Metric group for the operator. */
    protected transient InternalOperatorMetricGroup metrics;

    protected transient LatencyStats latencyStats;

    // ---------------- time handler ------------------

    protected transient ProcessingTimeService processingTimeService;

    protected transient RecordAttributes lastRecordAttributes1;
    protected transient RecordAttributes lastRecordAttributes2;

    public AbstractStreamOperator() {}

    public AbstractStreamOperator(StreamOperatorParameters<OUT> parameters) {
        if (parameters != null) {
            setup(
                    parameters.getContainingTask(),
                    parameters.getStreamConfig(),
                    parameters.getOutput());
            this.processingTimeService =
                    Preconditions.checkNotNull(parameters.getProcessingTimeService());
        }
    }

    // ------------------------------------------------------------------------
    //  Life Cycle
    // ------------------------------------------------------------------------

    protected void setup(
            StreamTask<?, ?> containingTask,
            StreamConfig config,
            Output<StreamRecord<OUT>> output) {
        final Environment environment = containingTask.getEnvironment();
        this.container = containingTask;
        this.config = config;
        this.output = output;
        this.metrics =
                environment
                        .getMetricGroup()
                        .getOrAddOperator(
                                config.getOperatorID(),
                                config.getOperatorName(),
                                config.getAdditionalMetricVariables());
        this.combinedWatermark = IndexedCombinedWatermarkStatus.forInputsCount(2);

        try {
            Configuration taskManagerConfig = environment.getTaskManagerInfo().getConfiguration();
            int historySize = taskManagerConfig.get(MetricOptions.LATENCY_HISTORY_SIZE);
            if (historySize <= 0) {
                LOG.warn(
                        "{} has been set to a value equal or below 0: {}. Using default.",
                        MetricOptions.LATENCY_HISTORY_SIZE,
                        historySize);
                historySize = MetricOptions.LATENCY_HISTORY_SIZE.defaultValue();
            }

            final String configuredGranularity =
                    taskManagerConfig.get(MetricOptions.LATENCY_SOURCE_GRANULARITY);
            LatencyStats.Granularity granularity;
            try {
                granularity =
                        LatencyStats.Granularity.valueOf(
                                configuredGranularity.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException iae) {
                granularity = LatencyStats.Granularity.OPERATOR;
                LOG.warn(
                        "Configured value {} option for {} is invalid. Defaulting to {}.",
                        configuredGranularity,
                        MetricOptions.LATENCY_SOURCE_GRANULARITY.key(),
                        granularity);
            }
            MetricGroup taskMetricGroup = this.metrics.getTaskMetricGroup();
            this.latencyStats =
                    new LatencyStats(
                            taskMetricGroup.addGroup("latency"),
                            historySize,
                            container.getIndexInSubtaskGroup(),
                            getOperatorID(),
                            granularity);
        } catch (Exception e) {
            LOG.warn("An error occurred while instantiating latency metrics.", e);
            this.latencyStats =
                    new LatencyStats(
                            UnregisteredMetricGroups.createUnregisteredTaskMetricGroup()
                                    .addGroup("latency"),
                            1,
                            0,
                            new OperatorID(),
                            LatencyStats.Granularity.SINGLE);
        }

        this.runtimeContext =
                new StreamingRuntimeContext(
                        environment,
                        environment.getAccumulatorRegistry().getUserMap(),
                        getMetricGroup(),
                        getOperatorID(),
                        getProcessingTimeService(),
                        null,
                        environment.getExternalResourceInfoProvider());

        stateKeySelector1 = config.getStatePartitioner(0, getUserCodeClassloader());
        stateKeySelector2 = config.getStatePartitioner(1, getUserCodeClassloader());

        lastRecordAttributes1 = RecordAttributes.EMPTY_RECORD_ATTRIBUTES;
        lastRecordAttributes2 = RecordAttributes.EMPTY_RECORD_ATTRIBUTES;
    }

    protected void setProcessingTimeService(ProcessingTimeService processingTimeService) {
        this.processingTimeService = Preconditions.checkNotNull(processingTimeService);
    }

    @Override
    public OperatorMetricGroup getMetricGroup() {
        return metrics;
    }

    /** Initialize necessary state components before initializing state components. */
    protected void beforeInitializeStateHandler() {}

    @Override
    public final void initializeState(StreamTaskStateInitializer streamTaskStateManager)
            throws Exception {

        final TypeSerializer<?> keySerializer =
                config.getStateKeySerializer(getUserCodeClassloader());

        final StreamTask<?, ?> containingTask = Preconditions.checkNotNull(getContainingTask());
        final CloseableRegistry streamTaskCloseableRegistry =
                Preconditions.checkNotNull(containingTask.getCancelables());

        final StreamOperatorStateContext context =
                streamTaskStateManager.streamOperatorStateContext(
                        getOperatorID(),
                        getClass().getSimpleName(),
                        getProcessingTimeService(),
                        this,
                        keySerializer,
                        streamTaskCloseableRegistry,
                        metrics,
                        config.getManagedMemoryFractionOperatorUseCaseOfSlot(
                                ManagedMemoryUseCase.STATE_BACKEND,
                                runtimeContext.getJobConfiguration(),
                                runtimeContext.getTaskManagerRuntimeInfo().getConfiguration(),
                                runtimeContext.getUserCodeClassLoader()),
                        isUsingCustomRawKeyedState(),
                        isAsyncKeyOrderedProcessingEnabled());
        stateHandler =
                new StreamOperatorStateHandler(
                        context, getExecutionConfig(), streamTaskCloseableRegistry);
        timeServiceManager =
                isAsyncKeyOrderedProcessingEnabled()
                        ? context.asyncInternalTimerServiceManager()
                        : context.internalTimerServiceManager();

        beforeInitializeStateHandler();
        stateHandler.initializeOperatorState(this);
        runtimeContext.setKeyedStateStore(stateHandler.getKeyedStateStore().orElse(null));
    }

    /**
     * Indicates whether or not implementations of this class is writing to the raw keyed state
     * streams on snapshots, using {@link #snapshotState(StateSnapshotContext)}. If yes, subclasses
     * should override this method to return {@code true}.
     *
     * <p>Subclasses need to explicitly indicate the use of raw keyed state because, internally, the
     * {@link AbstractStreamOperator} may attempt to read from it as well to restore heap-based
     * timers and ultimately fail with read errors. By setting this flag to {@code true}, this
     * allows the {@link AbstractStreamOperator} to know that the data written in the raw keyed
     * states were not written by the timer services, and skips the timer restore attempt.
     *
     * <p>Please refer to FLINK-19741 for further details.
     *
     * <p>TODO: this method can be removed once all timers are moved to be managed by state
     * backends.
     *
     * @return flag indicating whether or not this operator is writing to raw keyed state via {@link
     *     #snapshotState(StateSnapshotContext)}.
     */
    @Internal
    protected boolean isUsingCustomRawKeyedState() {
        return false;
    }

    /**
     * Indicates whether this operator is enabling the async state. Can be overridden by subclasses.
     */
    @Internal
    public boolean isAsyncKeyOrderedProcessingEnabled() {
        return false;
    }

    @Internal
    @Override
    public void setMailboxExecutor(MailboxExecutor mailboxExecutor) {
        this.mailboxExecutor = mailboxExecutor;
    }

    /**
     * Can be overridden to disable splittable timers for this particular operator even if config
     * option is enabled. By default, splittable timers are disabled.
     *
     * @return {@code true} if splittable timers should be used (subject to {@link
     *     CheckpointingOptions#isUnalignedCheckpointInterruptibleTimersEnabled(Configuration)}.
     *     {@code false} if splittable timers should never be used.
     */
    @Internal
    public boolean useInterruptibleTimers() {
        return false;
    }

    @Internal
    private boolean areInterruptibleTimersConfigured() {
        return CheckpointingOptions.isUnalignedCheckpointInterruptibleTimersEnabled(
                getContainingTask().getJobConfiguration());
    }

    /**
     * This method is called immediately before any elements are processed, it should contain the
     * operator's initialization logic, e.g. state initialization.
     *
     * <p>The default implementation does nothing.
     *
     * @throws Exception An exception in this method causes the operator to fail.
     */
    @Override
    public void open() throws Exception {
        if (useInterruptibleTimers()
                && areInterruptibleTimersConfigured()
                && getTimeServiceManager().isPresent()) {
            this.watermarkProcessor =
                    new MailboxWatermarkProcessor(
                            output, mailboxExecutor, getTimeServiceManager().get());
        }
    }

    @Override
    public void finish() throws Exception {}

    @Override
    public void close() throws Exception {
        if (stateHandler != null) {
            stateHandler.dispose();
        }
    }

    @Override
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        // the default implementation does nothing and accepts the checkpoint
        // this is purely for subclasses to override
    }

    @Override
    public OperatorSnapshotFutures snapshotState(
            long checkpointId,
            long timestamp,
            CheckpointOptions checkpointOptions,
            CheckpointStreamFactory factory)
            throws Exception {
        return stateHandler.snapshotState(
                this,
                Optional.ofNullable(timeServiceManager),
                getOperatorName(),
                checkpointId,
                timestamp,
                checkpointOptions,
                factory,
                isUsingCustomRawKeyedState(),
                isAsyncKeyOrderedProcessingEnabled());
    }

    /**
     * Stream operators with state, which want to participate in a snapshot need to override this
     * hook method.
     *
     * @param context context that provides information and means required for taking a snapshot
     */
    @Override
    public void snapshotState(StateSnapshotContext context) throws Exception {}

    /**
     * Stream operators with state which can be restored need to override this hook method.
     *
     * @param context context that allows to register different states.
     */
    @Override
    public void initializeState(StateInitializationContext context) throws Exception {}

    @Override
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        stateHandler.notifyCheckpointComplete(checkpointId);
    }

    @Override
    public void notifyCheckpointAborted(long checkpointId) throws Exception {
        stateHandler.notifyCheckpointAborted(checkpointId);
    }

    // ------------------------------------------------------------------------
    //  Properties and Services
    // ------------------------------------------------------------------------

    /**
     * Gets the execution config defined on the execution environment of the job to which this
     * operator belongs.
     *
     * @return The job's execution config.
     */
    public ExecutionConfig getExecutionConfig() {
        return container.getExecutionConfig();
    }

    public StreamConfig getOperatorConfig() {
        return config;
    }

    public StreamTask<?, ?> getContainingTask() {
        return container;
    }

    public ClassLoader getUserCodeClassloader() {
        return container.getUserCodeClassLoader();
    }

    /**
     * Return the operator name. If the runtime context has been set, then the task name with
     * subtask index is returned. Otherwise, the simple class name is returned.
     *
     * @return If runtime context is set, then return task name with subtask index. Otherwise return
     *     simple class name.
     */
    protected String getOperatorName() {
        if (runtimeContext != null) {
            return runtimeContext.getTaskInfo().getTaskNameWithSubtasks();
        } else {
            return getClass().getSimpleName();
        }
    }

    /**
     * Returns a context that allows the operator to query information about the execution and also
     * to interact with systems such as broadcast variables and managed state. This also allows to
     * register timers.
     */
    @VisibleForTesting
    public StreamingRuntimeContext getRuntimeContext() {
        return runtimeContext;
    }

    public <K> KeyedStateBackend<K> getKeyedStateBackend() {
        return stateHandler.getKeyedStateBackend();
    }

    @VisibleForTesting
    public OperatorStateBackend getOperatorStateBackend() {
        return stateHandler.getOperatorStateBackend();
    }

    /**
     * Returns the {@link ProcessingTimeService} responsible for getting the current processing time
     * and registering timers.
     */
    @VisibleForTesting
    public ProcessingTimeService getProcessingTimeService() {
        return processingTimeService;
    }

    /**
     * Creates a partitioned state handle, using the state backend configured for this task.
     *
     * @throws IllegalStateException Thrown, if the key/value state was already initialized.
     * @throws Exception Thrown, if the state backend cannot create the key/value state.
     */
    protected <S extends State> S getPartitionedState(StateDescriptor<S, ?> stateDescriptor)
            throws Exception {
        return getPartitionedState(
                VoidNamespace.INSTANCE, VoidNamespaceSerializer.INSTANCE, stateDescriptor);
    }

    protected <N, S extends State, T> S getOrCreateKeyedState(
            TypeSerializer<N> namespaceSerializer, StateDescriptor<S, T> stateDescriptor)
            throws Exception {
        return stateHandler.getOrCreateKeyedState(namespaceSerializer, stateDescriptor);
    }

    /**
     * Creates a partitioned state handle, using the state backend configured for this task.
     *
     * @throws IllegalStateException Thrown, if the key/value state was already initialized.
     * @throws Exception Thrown, if the state backend cannot create the key/value state.
     */
    public <S extends State, N> S getPartitionedState(
            N namespace,
            TypeSerializer<N> namespaceSerializer,
            StateDescriptor<S, ?> stateDescriptor)
            throws Exception {
        return stateHandler.getPartitionedState(namespace, namespaceSerializer, stateDescriptor);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setKeyContextElement1(StreamRecord record) throws Exception {
        setKeyContextElement(record, stateKeySelector1);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void setKeyContextElement2(StreamRecord record) throws Exception {
        setKeyContextElement(record, stateKeySelector2);
    }

    @Internal
    @Override
    public boolean hasKeyContext1() {
        return stateKeySelector1 != null;
    }

    @Internal
    @Override
    public boolean hasKeyContext2() {
        return stateKeySelector2 != null;
    }

    private <T> void setKeyContextElement(StreamRecord<T> record, KeySelector<T, ?> selector)
            throws Exception {
        if (selector != null) {
            Object key = selector.getKey(record.getValue());
            setCurrentKey(key);
        }
    }

    public void setCurrentKey(Object key) {
        stateHandler.setCurrentKey(key);
    }

    public Object getCurrentKey() {
        return stateHandler.getCurrentKey();
    }

    public KeyedStateStore getKeyedStateStore() {
        if (stateHandler == null) {
            return null;
        }
        return stateHandler.getKeyedStateStore().orElse(null);
    }

    protected KeySelector<?, ?> getStateKeySelector1() {
        return stateKeySelector1;
    }

    protected KeySelector<?, ?> getStateKeySelector2() {
        return stateKeySelector2;
    }

    // ------------------------------------------------------------------------
    //  Context and chaining properties
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    //  Metrics
    // ------------------------------------------------------------------------

    // ------- One input stream
    public void processLatencyMarker(LatencyMarker latencyMarker) throws Exception {
        reportOrForwardLatencyMarker(latencyMarker);
    }

    // ------- Two input stream
    public void processLatencyMarker1(LatencyMarker latencyMarker) throws Exception {
        reportOrForwardLatencyMarker(latencyMarker);
    }

    public void processLatencyMarker2(LatencyMarker latencyMarker) throws Exception {
        reportOrForwardLatencyMarker(latencyMarker);
    }

    protected void reportOrForwardLatencyMarker(LatencyMarker marker) {
        // all operators are tracking latencies
        this.latencyStats.reportLatency(marker);

        // everything except sinks forwards latency markers
        this.output.emitLatencyMarker(marker);
    }

    // ------------------------------------------------------------------------
    //  Watermark handling
    // ------------------------------------------------------------------------

    /**
     * Returns a {@link InternalTimerService} that can be used to query current processing time and
     * event time and to set timers. An operator can have several timer services, where each has its
     * own namespace serializer. Timer services are differentiated by the string key that is given
     * when requesting them, if you call this method with the same key multiple times you will get
     * the same timer service instance in subsequent requests.
     *
     * <p>Timers are always scoped to a key, the currently active key of a keyed stream operation.
     * When a timer fires, this key will also be set as the currently active key.
     *
     * <p>Each timer has attached metadata, the namespace. Different timer services can have a
     * different namespace type. If you don't need namespace differentiation you can use {@link
     * VoidNamespaceSerializer} as the namespace serializer.
     *
     * @param name The name of the requested timer service. If no service exists under the given
     *     name a new one will be created and returned.
     * @param namespaceSerializer {@code TypeSerializer} for the timer namespace.
     * @param triggerable The {@link Triggerable} that should be invoked when timers fire
     * @param <N> The type of the timer namespace.
     */
    public <K, N> InternalTimerService<N> getInternalTimerService(
            String name, TypeSerializer<N> namespaceSerializer, Triggerable<K, N> triggerable) {
        if (timeServiceManager == null) {
            throw new RuntimeException("The timer service has not been initialized.");
        }
        @SuppressWarnings("unchecked")
        InternalTimeServiceManager<K> keyedTimeServiceHandler =
                (InternalTimeServiceManager<K>) timeServiceManager;
        TypeSerializer<K> keySerializer = stateHandler.getKeySerializer();
        checkState(keySerializer != null, "Timers can only be used on keyed operators.");
        return keyedTimeServiceHandler.getInternalTimerService(
                name, keySerializer, namespaceSerializer, triggerable);
    }

    public void processWatermark(Watermark mark) throws Exception {
        if (watermarkProcessor != null) {
            watermarkProcessor.emitWatermarkInsideMailbox(mark);
        } else {
            emitWatermarkDirectly(mark);
        }
    }

    private void emitWatermarkDirectly(Watermark mark) throws Exception {
        if (timeServiceManager != null) {
            timeServiceManager.advanceWatermark(mark);
        }
        output.emitWatermark(mark);
    }

    private void processWatermark(Watermark mark, int index) throws Exception {
        if (combinedWatermark.updateWatermark(index, mark.getTimestamp())) {
            processWatermark(new Watermark(combinedWatermark.getCombinedWatermark()));
        }
    }

    public void processWatermark1(Watermark mark) throws Exception {
        processWatermark(mark, 0);
    }

    public void processWatermark2(Watermark mark) throws Exception {
        processWatermark(mark, 1);
    }

    public void processWatermarkStatus(WatermarkStatus watermarkStatus) throws Exception {
        output.emitWatermarkStatus(watermarkStatus);
    }

    protected void processWatermarkStatus(WatermarkStatus watermarkStatus, int index)
            throws Exception {
        boolean wasIdle = combinedWatermark.isIdle();
        if (combinedWatermark.updateStatus(index, watermarkStatus.isIdle())) {
            processWatermark(new Watermark(combinedWatermark.getCombinedWatermark()));
        }
        if (wasIdle != combinedWatermark.isIdle()) {
            output.emitWatermarkStatus(watermarkStatus);
        }
    }

    public final void processWatermarkStatus1(WatermarkStatus watermarkStatus) throws Exception {
        processWatermarkStatus(watermarkStatus, 0);
    }

    public final void processWatermarkStatus2(WatermarkStatus watermarkStatus) throws Exception {
        processWatermarkStatus(watermarkStatus, 1);
    }

    @Override
    public OperatorID getOperatorID() {
        return config.getOperatorID();
    }

    protected Optional<InternalTimeServiceManager<?>> getTimeServiceManager() {
        return Optional.ofNullable(timeServiceManager);
    }

    @Experimental
    public void processRecordAttributes(RecordAttributes recordAttributes) throws Exception {
        output.emitRecordAttributes(
                new RecordAttributesBuilder(Collections.singletonList(recordAttributes)).build());
    }

    @Experimental
    public void processRecordAttributes1(RecordAttributes recordAttributes) {
        lastRecordAttributes1 = recordAttributes;
        output.emitRecordAttributes(
                new RecordAttributesBuilder(
                                Arrays.asList(lastRecordAttributes1, lastRecordAttributes2))
                        .build());
    }

    @Experimental
    public void processRecordAttributes2(RecordAttributes recordAttributes) {
        lastRecordAttributes2 = recordAttributes;
        output.emitRecordAttributes(
                new RecordAttributesBuilder(
                                Arrays.asList(lastRecordAttributes1, lastRecordAttributes2))
                        .build());
    }

    @Experimental
    public void processWatermark(WatermarkEvent watermark) throws Exception {
        output.emitWatermark(watermark);
    }

    @Experimental
    public void processWatermark1(WatermarkEvent watermark) throws Exception {
        output.emitWatermark(watermark);
    }

    @Experimental
    public void processWatermark2(WatermarkEvent watermark) throws Exception {
        output.emitWatermark(watermark);
    }
}
