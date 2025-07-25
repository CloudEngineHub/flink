/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.util;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.typeutils.TypeExtractor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.CheckpointType;
import org.apache.flink.runtime.checkpoint.OperatorStateRepartitioner;
import org.apache.flink.runtime.checkpoint.OperatorSubtaskState;
import org.apache.flink.runtime.checkpoint.RoundRobinOperatorStateRepartitioner;
import org.apache.flink.runtime.checkpoint.SnapshotType;
import org.apache.flink.runtime.checkpoint.StateAssignmentOperation;
import org.apache.flink.runtime.checkpoint.StateObjectCollection;
import org.apache.flink.runtime.checkpoint.SubTaskInitializationMetricsBuilder;
import org.apache.flink.runtime.checkpoint.TaskStateSnapshot;
import org.apache.flink.runtime.event.WatermarkEvent;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.runtime.operators.testutils.MockEnvironment;
import org.apache.flink.runtime.operators.testutils.MockEnvironmentBuilder;
import org.apache.flink.runtime.operators.testutils.MockInputSplitProvider;
import org.apache.flink.runtime.state.CheckpointStorage;
import org.apache.flink.runtime.state.CheckpointStorageAccess;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupStatePartitionStreamProvider;
import org.apache.flink.runtime.state.KeyedStateHandle;
import org.apache.flink.runtime.state.OperatorStateHandle;
import org.apache.flink.runtime.state.PriorityQueueSetFactory;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.TestTaskStateManager;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.runtime.state.storage.JobManagerCheckpointStorage;
import org.apache.flink.runtime.state.ttl.MockTtlTimeProvider;
import org.apache.flink.runtime.state.ttl.TtlTimeProvider;
import org.apache.flink.runtime.taskmanager.NoOpTaskOperatorEventGateway;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.AbstractStreamOperator;
import org.apache.flink.streaming.api.operators.AbstractStreamOperatorTest;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.InternalTimeServiceManagerImpl;
import org.apache.flink.streaming.api.operators.KeyContext;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFinalizer;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFutures;
import org.apache.flink.streaming.api.operators.Output;
import org.apache.flink.streaming.api.operators.SimpleOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.streaming.api.operators.StreamOperatorFactory;
import org.apache.flink.streaming.api.operators.StreamOperatorFactoryUtil;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializer;
import org.apache.flink.streaming.api.operators.StreamTaskStateInitializerImpl;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.RecordAttributes;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.OperatorEventDispatcherImpl;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.runtime.tasks.StreamTaskCancellationContext;
import org.apache.flink.streaming.runtime.tasks.TestProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox;
import org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailboxImpl;
import org.apache.flink.streaming.runtime.watermarkstatus.WatermarkStatus;
import org.apache.flink.util.OutputTag;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.clock.SystemClock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.apache.flink.streaming.api.operators.StreamOperatorUtils.setProcessingTimeService;
import static org.apache.flink.streaming.api.operators.StreamOperatorUtils.setupStreamOperator;
import static org.apache.flink.util.Preconditions.checkState;

/** Base class for {@code AbstractStreamOperator} test harnesses. */
public class AbstractStreamOperatorTestHarness<OUT> implements AutoCloseable {

    protected StreamOperator<OUT> operator;

    protected final StreamOperatorFactory<OUT> factory;

    protected final ConcurrentLinkedQueue<Object> outputList;

    protected final Map<OutputTag<?>, ConcurrentLinkedQueue<Object>> sideOutputLists;

    protected final StreamConfig config;

    protected final ExecutionConfig executionConfig;

    protected final TestProcessingTimeService processingTimeService;

    protected final MockTtlTimeProvider ttlTimeProvider;

    protected final MockStreamTask<OUT, ?> mockTask;

    protected final TestTaskStateManager taskStateManager;

    final MockEnvironment environment;

    private final Optional<MockEnvironment> internalEnvironment;

    protected StreamTaskStateInitializer streamTaskStateInitializer;

    private final TaskMailbox taskMailbox;

    // use this as default for tests
    protected StateBackend stateBackend = new HashMapStateBackend();

    private CheckpointStorageAccess checkpointStorageAccess =
            new JobManagerCheckpointStorage().createCheckpointStorage(new JobID());

    private final Object checkpointLock;

    private static final OperatorStateRepartitioner<OperatorStateHandle>
            operatorStateRepartitioner = RoundRobinOperatorStateRepartitioner.INSTANCE;

    private InternalTimeServiceManagerImpl<?> timeServiceManager;
    private InternalTimeServiceManager.Provider timeServiceManagerProvider =
            new InternalTimeServiceManager.Provider() {
                @Override
                public <K> InternalTimeServiceManager<K> create(
                        TaskIOMetricGroup taskIOMetricGroup,
                        PriorityQueueSetFactory factory,
                        KeyGroupRange keyGroupRange,
                        ClassLoader userClassloader,
                        KeyContext keyContext,
                        ProcessingTimeService processingTimeService,
                        Iterable<KeyGroupStatePartitionStreamProvider> rawKeyedStates,
                        StreamTaskCancellationContext cancellationContext)
                        throws Exception {
                    InternalTimeServiceManagerImpl<K> typedTimeServiceManager =
                            InternalTimeServiceManagerImpl.create(
                                    taskIOMetricGroup,
                                    factory,
                                    keyGroupRange,
                                    userClassloader,
                                    keyContext,
                                    processingTimeService,
                                    rawKeyedStates,
                                    cancellationContext);
                    if (timeServiceManager == null) {
                        timeServiceManager = typedTimeServiceManager;
                    }
                    return typedTimeServiceManager;
                }
            };

    /** Whether setup() was called on the operator. This is reset when calling close(). */
    private boolean setupCalled = false;

    private boolean initializeCalled = false;

    private volatile boolean wasFailedExternally = false;

    private long restoredCheckpointId = 0;

    public AbstractStreamOperatorTestHarness(
            StreamOperator<OUT> operator, int maxParallelism, int parallelism, int subtaskIndex)
            throws Exception {
        this(operator, maxParallelism, parallelism, subtaskIndex, new OperatorID());
    }

    public AbstractStreamOperatorTestHarness(
            StreamOperator<OUT> operator,
            int maxParallelism,
            int parallelism,
            int subtaskIndex,
            OperatorID operatorID)
            throws Exception {
        this(
                operator,
                SimpleOperatorFactory.of(operator),
                new MockEnvironmentBuilder()
                        .setTaskName("MockTask")
                        .setManagedMemorySize(3 * 1024 * 1024)
                        .setInputSplitProvider(new MockInputSplitProvider())
                        .setBufferSize(1024)
                        .setMaxParallelism(maxParallelism)
                        .setParallelism(parallelism)
                        .setSubtaskIndex(subtaskIndex)
                        .build(),
                true,
                operatorID);
    }

    public AbstractStreamOperatorTestHarness(
            StreamOperatorFactory<OUT> factory, MockEnvironment env) throws Exception {
        this(null, factory, env, false, new OperatorID());
    }

    public AbstractStreamOperatorTestHarness(
            StreamOperatorFactory<OUT> factory,
            int maxParallelism,
            int parallelism,
            int subtaskIndex)
            throws Exception {
        this(factory, maxParallelism, parallelism, subtaskIndex, new OperatorID());
    }

    public AbstractStreamOperatorTestHarness(
            StreamOperatorFactory<OUT> factory,
            int maxParallelism,
            int parallelism,
            int subtaskIndex,
            OperatorID operatorID)
            throws Exception {
        this(
                null,
                factory,
                new MockEnvironmentBuilder()
                        .setTaskName("MockTask")
                        .setManagedMemorySize(3 * 1024 * 1024)
                        .setInputSplitProvider(new MockInputSplitProvider())
                        .setBufferSize(1024)
                        .setMaxParallelism(maxParallelism)
                        .setParallelism(parallelism)
                        .setSubtaskIndex(subtaskIndex)
                        .build(),
                true,
                operatorID);
    }

    public AbstractStreamOperatorTestHarness(StreamOperator<OUT> operator, MockEnvironment env)
            throws Exception {
        this(operator, SimpleOperatorFactory.of(operator), env, false, new OperatorID());
    }

    public AbstractStreamOperatorTestHarness(
            StreamOperator<OUT> operator, String taskName, OperatorID operatorID) throws Exception {
        this(
                operator,
                SimpleOperatorFactory.of(operator),
                new MockEnvironmentBuilder()
                        .setTaskName(taskName)
                        .setManagedMemorySize(3 * 1024 * 1024)
                        .setInputSplitProvider(new MockInputSplitProvider())
                        .setBufferSize(1024)
                        .setMaxParallelism(1)
                        .setParallelism(1)
                        .setSubtaskIndex(0)
                        .build(),
                false,
                operatorID);
    }

    private AbstractStreamOperatorTestHarness(
            StreamOperator<OUT> operator,
            StreamOperatorFactory<OUT> factory,
            MockEnvironment env,
            boolean environmentIsInternal,
            OperatorID operatorID)
            throws Exception {
        this.operator = operator;
        this.factory = factory;
        this.outputList = new ConcurrentLinkedQueue<>();
        this.sideOutputLists = new HashMap<>();

        Configuration underlyingConfig = env.getTaskConfiguration();
        this.config = new StreamConfig(underlyingConfig);
        this.config.setOperatorID(operatorID);
        this.config.setStateBackendUsesManagedMemory(true);
        this.config.setManagedMemoryFractionOperatorOfUseCase(
                ManagedMemoryUseCase.STATE_BACKEND, 1.0);
        this.config.setManagedMemoryFractionOperatorOfUseCase(ManagedMemoryUseCase.OPERATOR, 1.0);
        this.executionConfig = env.getExecutionConfig();
        this.checkpointLock = new Object();

        this.environment = Preconditions.checkNotNull(env);

        this.taskStateManager = (TestTaskStateManager) env.getTaskStateManager();
        this.internalEnvironment =
                environmentIsInternal ? Optional.of(environment) : Optional.empty();

        processingTimeService = new TestProcessingTimeService();
        processingTimeService.setCurrentTime(0);

        ttlTimeProvider = new MockTtlTimeProvider();
        ttlTimeProvider.setCurrentTimestamp(0);

        this.streamTaskStateInitializer =
                createStreamTaskStateManager(
                        environment, stateBackend, ttlTimeProvider, timeServiceManagerProvider);

        BiConsumer<String, Throwable> handleAsyncException =
                (message, t) -> {
                    wasFailedExternally = true;
                };

        this.taskMailbox = new TaskMailboxImpl();

        // TODO remove this once we introduce AbstractStreamOperatorTestHarnessBuilder.
        try {
            this.checkpointStorageAccess = environment.getCheckpointStorageAccess();
        } catch (NullPointerException | UnsupportedOperationException e) {
            // cannot get checkpoint storage from environment, use default one.
        }

        mockTask =
                new MockStreamTaskBuilder(env)
                        .setCheckpointLock(checkpointLock)
                        .setConfig(config)
                        .setExecutionConfig(executionConfig)
                        .setStreamTaskStateInitializer(streamTaskStateInitializer)
                        .setCheckpointStorage(checkpointStorageAccess)
                        .setTimerService(processingTimeService)
                        .setHandleAsyncException(handleAsyncException)
                        .setTaskMailbox(taskMailbox)
                        .build();
    }

    private StreamTaskStateInitializer createStreamTaskStateManager(
            Environment env,
            StateBackend stateBackend,
            TtlTimeProvider ttlTimeProvider,
            InternalTimeServiceManager.Provider timeServiceManagerProvider) {
        return new StreamTaskStateInitializerImpl(
                env,
                stateBackend,
                new SubTaskInitializationMetricsBuilder(
                        SystemClock.getInstance().absoluteTimeMillis()),
                ttlTimeProvider,
                timeServiceManagerProvider,
                StreamTaskCancellationContext.alwaysRunning());
    }

    public void setStateBackend(StateBackend stateBackend) {
        this.stateBackend = stateBackend;

        if (stateBackend instanceof CheckpointStorage) {
            setCheckpointStorage((CheckpointStorage) stateBackend);
        }
    }

    public void setCheckpointStorage(CheckpointStorage storage) {
        if (stateBackend instanceof CheckpointStorage) {
            return;
        }

        try {
            this.checkpointStorageAccess = storage.createCheckpointStorage(new JobID());
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * @deprecated Checkpoint lock in {@link StreamTask} is replaced by {@link
     *     org.apache.flink.streaming.runtime.tasks.StreamTaskActionExecutor
     *     StreamTaskActionExecutor}.
     */
    @Deprecated
    public Object getCheckpointLock() {
        return mockTask.getCheckpointLock();
    }

    public MockEnvironment getEnvironment() {
        return environment;
    }

    public ExecutionConfig getExecutionConfig() {
        return executionConfig;
    }

    public StreamConfig getStreamConfig() {
        return config;
    }

    public void setRestoredCheckpointId(long restoredCheckpointId) {
        this.restoredCheckpointId = restoredCheckpointId;
    }

    /** Get all the output from the task. This contains StreamRecords and Events interleaved. */
    public ConcurrentLinkedQueue<Object> getOutput() {
        return outputList;
    }

    @SuppressWarnings("unchecked")
    public Collection<StreamRecord<OUT>> getRecordOutput() {
        return outputList.stream()
                .filter(element -> element instanceof StreamRecord)
                .map(element -> (StreamRecord<OUT>) element)
                .collect(Collectors.toList());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <X> ConcurrentLinkedQueue<StreamRecord<X>> getSideOutput(OutputTag<X> tag) {
        return (ConcurrentLinkedQueue) sideOutputLists.get(tag);
    }

    /** Get only the {@link StreamRecord StreamRecords} emitted by the operator. */
    @SuppressWarnings("unchecked")
    public List<StreamRecord<? extends OUT>> extractOutputStreamRecords() {
        List<StreamRecord<? extends OUT>> resultElements = new LinkedList<>();
        for (Object e : getOutput()) {
            if (e instanceof StreamRecord) {
                resultElements.add((StreamRecord<OUT>) e);
            }
        }
        return resultElements;
    }

    /** Get the list of OUT values emitted by the operator. */
    public List<OUT> extractOutputValues() {
        List<StreamRecord<? extends OUT>> streamRecords = extractOutputStreamRecords();
        List<OUT> outputValues = new ArrayList<>();
        for (StreamRecord<? extends OUT> streamRecord : streamRecords) {
            outputValues.add(streamRecord.getValue());
        }
        return outputValues;
    }

    /**
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperatorUtils#setupStreamOperator(AbstractStreamOperator,
     * StreamTask, StreamConfig, Output)} ()}.
     */
    public void setup() {
        setup(null);
    }

    /**
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperatorUtils#setupStreamOperator(AbstractStreamOperator,
     * StreamTask, StreamConfig, Output)} ()}.
     */
    public void setup(TypeSerializer<OUT> outputSerializer) {
        if (!setupCalled) {
            streamTaskStateInitializer =
                    createStreamTaskStateManager(
                            environment, stateBackend, ttlTimeProvider, timeServiceManagerProvider);
            mockTask.setStreamTaskStateInitializer(streamTaskStateInitializer);

            if (operator == null) {
                this.operator =
                        StreamOperatorFactoryUtil.createOperator(
                                        factory,
                                        mockTask,
                                        config,
                                        new MockOutput(outputSerializer),
                                        new OperatorEventDispatcherImpl(
                                                this.getClass().getClassLoader(),
                                                new NoOpTaskOperatorEventGateway()))
                                .f0;
            } else {
                if (operator instanceof AbstractStreamOperator) {
                    setProcessingTimeService(
                            (AbstractStreamOperator) operator, processingTimeService);
                    setupStreamOperator(
                            (AbstractStreamOperator) operator,
                            mockTask,
                            config,
                            new MockOutput(outputSerializer));
                }
            }
            setupCalled = true;
            this.mockTask.init();
        }
    }

    /**
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperator#initializeState(StreamTaskStateInitializer)}.
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperatorUtils#setupStreamOperator(AbstractStreamOperator,
     * StreamTask, StreamConfig, Output)} if it was not called before.
     */
    public void initializeState(OperatorSubtaskState operatorStateHandles) throws Exception {
        initializeState(operatorStateHandles, null);
    }

    public void initializeState(String operatorStateSnapshotPath) throws Exception {
        initializeState(OperatorSnapshotUtil.readStateHandle(operatorStateSnapshotPath));
    }

    public void initializeEmptyState() throws Exception {
        initializeState((OperatorSubtaskState) null);
    }

    /**
     * Returns the reshaped the state handles to include only those key-group states in the local
     * key-group range and the operator states that would be assigned to the local subtask.
     */
    public static OperatorSubtaskState repartitionOperatorState(
            final OperatorSubtaskState operatorStateHandles,
            final int numKeyGroups,
            final int oldParallelism,
            final int newParallelism,
            final int subtaskIndex) {

        Preconditions.checkNotNull(
                operatorStateHandles, "the previous operatorStateHandles should not be null.");

        // create a new OperatorStateHandles that only contains the state for our key-groups

        List<KeyGroupRange> keyGroupPartitions =
                StateAssignmentOperation.createKeyGroupPartitions(numKeyGroups, newParallelism);

        KeyGroupRange localKeyGroupRange = keyGroupPartitions.get(subtaskIndex);

        List<KeyedStateHandle> localManagedKeyGroupState = new ArrayList<>();
        StateAssignmentOperation.extractIntersectingState(
                operatorStateHandles.getManagedKeyedState(),
                localKeyGroupRange,
                localManagedKeyGroupState);

        List<KeyedStateHandle> localRawKeyGroupState = new ArrayList<>();
        StateAssignmentOperation.extractIntersectingState(
                operatorStateHandles.getRawKeyedState(), localKeyGroupRange, localRawKeyGroupState);

        StateObjectCollection<OperatorStateHandle> managedOperatorStates =
                operatorStateHandles.getManagedOperatorState();
        Collection<OperatorStateHandle> localManagedOperatorState;

        if (!managedOperatorStates.isEmpty()) {
            List<List<OperatorStateHandle>> managedOperatorState =
                    managedOperatorStates.stream()
                            .map(Collections::singletonList)
                            .collect(Collectors.toList());

            localManagedOperatorState =
                    operatorStateRepartitioner
                            .repartitionState(managedOperatorState, oldParallelism, newParallelism)
                            .get(subtaskIndex);
        } else {
            localManagedOperatorState = Collections.emptyList();
        }

        StateObjectCollection<OperatorStateHandle> rawOperatorStates =
                operatorStateHandles.getRawOperatorState();
        Collection<OperatorStateHandle> localRawOperatorState;

        if (!rawOperatorStates.isEmpty()) {
            List<List<OperatorStateHandle>> rawOperatorState =
                    rawOperatorStates.stream()
                            .map(Collections::singletonList)
                            .collect(Collectors.toList());

            localRawOperatorState =
                    operatorStateRepartitioner
                            .repartitionState(rawOperatorState, oldParallelism, newParallelism)
                            .get(subtaskIndex);
        } else {
            localRawOperatorState = Collections.emptyList();
        }

        return OperatorSubtaskState.builder()
                .setManagedOperatorState(
                        new StateObjectCollection<>(
                                nullToEmptyCollection(localManagedOperatorState)))
                .setRawOperatorState(
                        new StateObjectCollection<>(nullToEmptyCollection(localRawOperatorState)))
                .setManagedKeyedState(
                        new StateObjectCollection<>(
                                nullToEmptyCollection(localManagedKeyGroupState)))
                .setRawKeyedState(
                        new StateObjectCollection<>(nullToEmptyCollection(localRawKeyGroupState)))
                .build();
    }

    /**
     * Calls {@link org.apache.flink.streaming.api.operators.StreamOperator#initializeState()}.
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperatorUtils#setupStreamOperator(AbstractStreamOperator,
     * StreamTask, StreamConfig, Output)} if it was not called before.
     *
     * @param jmOperatorStateHandles the primary state (owned by JM)
     * @param tmOperatorStateHandles the (optional) local state (owned by TM) or null.
     * @throws Exception
     */
    public void initializeState(
            OperatorSubtaskState jmOperatorStateHandles,
            OperatorSubtaskState tmOperatorStateHandles)
            throws Exception {

        checkState(
                !initializeCalled,
                "TestHarness has already been initialized. Have you "
                        + "opened this harness before initializing it?");
        if (!setupCalled) {
            setup();
        }

        if (jmOperatorStateHandles != null) {

            TaskStateSnapshot jmTaskStateSnapshot = new TaskStateSnapshot();
            jmTaskStateSnapshot.putSubtaskStateByOperatorID(
                    operator.getOperatorID(), jmOperatorStateHandles);

            taskStateManager.setReportedCheckpointId(restoredCheckpointId);
            taskStateManager.setJobManagerTaskStateSnapshotsByCheckpointId(
                    Collections.singletonMap(restoredCheckpointId, jmTaskStateSnapshot));

            if (tmOperatorStateHandles != null) {
                TaskStateSnapshot tmTaskStateSnapshot = new TaskStateSnapshot();
                tmTaskStateSnapshot.putSubtaskStateByOperatorID(
                        operator.getOperatorID(), tmOperatorStateHandles);
                taskStateManager.setTaskManagerTaskStateSnapshotsByCheckpointId(
                        Collections.singletonMap(restoredCheckpointId, tmTaskStateSnapshot));
            }
        }

        operator.initializeState(
                mockTask.createStreamTaskStateInitializer(
                        new SubTaskInitializationMetricsBuilder(
                                SystemClock.getInstance().absoluteTimeMillis())));
        initializeCalled = true;
    }

    private static <T> Collection<T> nullToEmptyCollection(Collection<T> collection) {
        return collection != null ? collection : Collections.<T>emptyList();
    }

    /**
     * Takes the different {@link OperatorSubtaskState} created by calling {@link #snapshot(long,
     * long)} on different instances of {@link AbstractStreamOperatorTestHarness} (each one
     * representing one subtask) and repacks them into a single {@link OperatorSubtaskState} so that
     * the parallelism of the test can change arbitrarily (i.e. be able to scale both up and down).
     *
     * <p>After repacking the partial states, remember to use {@link
     * #repartitionOperatorState(OperatorSubtaskState, int, int, int, int)} to reshape the state
     * handles to include only those key-group states in the local key-group range and the operator
     * states that would be assigned to the local subtask. Bear in mind that for parallelism greater
     * than one, you have to use the constructor {@link
     * #AbstractStreamOperatorTestHarness(StreamOperator, int, int, int)}.
     *
     * <p><b>NOTE: </b> each of the {@code handles} in the argument list is assumed to be from a
     * single task of a single operator (i.e. chain length of one).
     *
     * <p>For an example of how to use it, have a look at {@link
     * AbstractStreamOperatorTest#testStateAndTimerStateShufflingScalingDown()}.
     *
     * @param handles the different states to be merged.
     * @return the resulting state, or {@code null} if no partial states are specified.
     */
    public static OperatorSubtaskState repackageState(OperatorSubtaskState... handles)
            throws Exception {

        if (handles.length < 1) {
            return null;
        } else if (handles.length == 1) {
            return handles[0];
        }

        List<OperatorStateHandle> mergedManagedOperatorState = new ArrayList<>(handles.length);
        List<OperatorStateHandle> mergedRawOperatorState = new ArrayList<>(handles.length);

        List<KeyedStateHandle> mergedManagedKeyedState = new ArrayList<>(handles.length);
        List<KeyedStateHandle> mergedRawKeyedState = new ArrayList<>(handles.length);

        for (OperatorSubtaskState handle : handles) {

            Collection<OperatorStateHandle> managedOperatorState = handle.getManagedOperatorState();
            Collection<OperatorStateHandle> rawOperatorState = handle.getRawOperatorState();
            Collection<KeyedStateHandle> managedKeyedState = handle.getManagedKeyedState();
            Collection<KeyedStateHandle> rawKeyedState = handle.getRawKeyedState();

            mergedManagedOperatorState.addAll(managedOperatorState);
            mergedRawOperatorState.addAll(rawOperatorState);
            mergedManagedKeyedState.addAll(managedKeyedState);
            mergedRawKeyedState.addAll(rawKeyedState);
        }

        return OperatorSubtaskState.builder()
                .setManagedOperatorState(new StateObjectCollection<>(mergedManagedOperatorState))
                .setRawOperatorState(new StateObjectCollection<>(mergedRawOperatorState))
                .setManagedKeyedState(new StateObjectCollection<>(mergedManagedKeyedState))
                .setRawKeyedState(new StateObjectCollection<>(mergedRawKeyedState))
                .build();
    }

    /**
     * Calls {@link StreamOperator#open()}. This also calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperatorUtils#setupStreamOperator(AbstractStreamOperator,
     * StreamTask, StreamConfig, Output)} if it was not called before.
     */
    public void open() throws Exception {
        if (!initializeCalled) {
            initializeEmptyState();
        }
        operator.open();
    }

    /** Calls {@link StreamOperator#prepareSnapshotPreBarrier(long)}. */
    public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
        operator.prepareSnapshotPreBarrier(checkpointId);
    }

    /**
     * Calls {@link StreamOperator#snapshotState(long, long, CheckpointOptions,
     * org.apache.flink.runtime.state.CheckpointStreamFactory)}.
     */
    public OperatorSubtaskState snapshot(long checkpointId, long timestamp) throws Exception {
        return snapshotWithLocalState(checkpointId, timestamp).getJobManagerOwnedState();
    }

    /**
     * Calls {@link StreamOperator#snapshotState(long, long, CheckpointOptions,
     * org.apache.flink.runtime.state.CheckpointStreamFactory)}.
     */
    public OperatorSnapshotFinalizer snapshotWithLocalState(long checkpointId, long timestamp)
            throws Exception {
        return snapshotWithLocalState(checkpointId, timestamp, CheckpointType.CHECKPOINT);
    }

    /**
     * Calls {@link StreamOperator#snapshotState(long, long, CheckpointOptions,
     * org.apache.flink.runtime.state.CheckpointStreamFactory)}.
     */
    public OperatorSnapshotFinalizer snapshotWithLocalState(
            long checkpointId, long timestamp, SnapshotType checkpointType) throws Exception {

        CheckpointStorageLocationReference locationReference =
                CheckpointStorageLocationReference.getDefault();
        OperatorSnapshotFutures operatorStateResult =
                operator.snapshotState(
                        checkpointId,
                        timestamp,
                        new CheckpointOptions(checkpointType, locationReference),
                        checkpointStorageAccess.resolveCheckpointStorageLocation(
                                checkpointId, locationReference));

        return new OperatorSnapshotFinalizer(operatorStateResult);
    }

    /**
     * Calls {@link
     * org.apache.flink.streaming.api.operators.StreamOperator#notifyCheckpointComplete(long)} ()}.
     */
    public void notifyOfCompletedCheckpoint(long checkpointId) throws Exception {
        operator.notifyCheckpointComplete(checkpointId);
    }

    /** Calls finish and close on the operator. */
    public void close() throws Exception {
        if (processingTimeService != null) {
            processingTimeService.shutdownService();
        }
        setupCalled = false;
        operator.finish();
        operator.close();

        if (internalEnvironment.isPresent()) {
            internalEnvironment.get().close();
        }
        mockTask.cleanUpInternal();
    }

    public AbstractStreamOperator<OUT> getOperator() {
        return (AbstractStreamOperator<OUT>) operator;
    }

    public StreamOperatorFactory<OUT> getOperatorFactory() {
        return factory;
    }

    public void advanceTime(long delta) throws Exception {
        processingTimeService.advance(delta);
    }

    public void setProcessingTime(long time) throws Exception {
        processingTimeService.setCurrentTime(time);
    }

    public void setStateTtlProcessingTime(long timeStamp) {
        ttlTimeProvider.setCurrentTimestamp(timeStamp);
    }

    public long getProcessingTime() {
        return processingTimeService.getCurrentProcessingTime();
    }

    public boolean wasFailedExternally() {
        return wasFailedExternally;
    }

    @VisibleForTesting
    public int numProcessingTimeTimers() {
        if (timeServiceManager != null) {
            return timeServiceManager.numProcessingTimeTimers();
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @VisibleForTesting
    public int numEventTimeTimers() {
        if (timeServiceManager != null) {
            return timeServiceManager.numEventTimeTimers();
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @VisibleForTesting
    public TestProcessingTimeService getProcessingTimeService() {
        return processingTimeService;
    }

    @VisibleForTesting
    public TaskMailbox getTaskMailbox() {
        return taskMailbox;
    }

    public void setTimeServiceManagerProvider(
            InternalTimeServiceManager.Provider timeServiceManagerProvider) {
        this.timeServiceManagerProvider = timeServiceManagerProvider;
    }

    class MockOutput implements Output<StreamRecord<OUT>> {

        private TypeSerializer<OUT> outputSerializer;

        private TypeSerializer sideOutputSerializer;

        MockOutput() {
            this(null);
        }

        MockOutput(TypeSerializer<OUT> outputSerializer) {
            this.outputSerializer = outputSerializer;
        }

        @Override
        public void emitWatermark(Watermark mark) {
            outputList.add(mark);
        }

        @Override
        public void emitWatermarkStatus(WatermarkStatus watermarkStatus) {
            outputList.add(watermarkStatus);
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) {
            outputList.add(latencyMarker);
        }

        @Override
        public void emitRecordAttributes(RecordAttributes recordAttributes) {
            outputList.add(recordAttributes);
        }

        @Override
        public void emitWatermark(WatermarkEvent watermark) {
            outputList.add(watermark);
        }

        @Override
        public void collect(StreamRecord<OUT> element) {
            if (outputSerializer == null) {
                outputSerializer =
                        TypeExtractor.getForObject(element.getValue())
                                .createSerializer(executionConfig.getSerializerConfig());
            }
            if (element.hasTimestamp()) {
                outputList.add(
                        new StreamRecord<>(
                                outputSerializer.copy(element.getValue()), element.getTimestamp()));
            } else {
                outputList.add(new StreamRecord<>(outputSerializer.copy(element.getValue())));
            }
        }

        @Override
        public <X> void collect(OutputTag<X> outputTag, StreamRecord<X> record) {
            sideOutputSerializer =
                    outputTag.getTypeInfo().createSerializer(executionConfig.getSerializerConfig());

            ConcurrentLinkedQueue<Object> sideOutputList = sideOutputLists.get(outputTag);
            if (sideOutputList == null) {
                sideOutputList = new ConcurrentLinkedQueue<>();
                sideOutputLists.put(outputTag, sideOutputList);
            }
            if (record.hasTimestamp()) {
                sideOutputList.add(
                        new StreamRecord<>(
                                sideOutputSerializer.copy(record.getValue()),
                                record.getTimestamp()));
            } else {
                sideOutputList.add(
                        new StreamRecord<>(sideOutputSerializer.copy(record.getValue())));
            }
        }

        @Override
        public void close() {
            // ignore
        }
    }
}
