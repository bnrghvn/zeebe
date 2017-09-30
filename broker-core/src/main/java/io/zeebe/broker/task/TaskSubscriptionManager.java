/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.task;

import static io.zeebe.broker.logstreams.LogStreamServiceNames.SNAPSHOT_STORAGE_SERVICE;
import static io.zeebe.broker.logstreams.processor.StreamProcessorIds.TASK_LOCK_STREAM_PROCESSOR_ID;
import static io.zeebe.broker.system.SystemServiceNames.ACTOR_SCHEDULER_SERVICE;
import static io.zeebe.broker.system.SystemServiceNames.COUNTERS_MANAGER_SERVICE;
import static io.zeebe.broker.task.TaskQueueServiceNames.taskQueueLockStreamProcessorServiceName;
import static io.zeebe.util.EnsureUtil.ensureNotNull;
import static io.zeebe.util.buffer.BufferUtil.bufferAsString;
import static io.zeebe.util.buffer.BufferUtil.cloneBuffer;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import io.zeebe.broker.logstreams.processor.StreamProcessorService;
import io.zeebe.broker.task.processor.LockTaskStreamProcessor;
import io.zeebe.broker.task.processor.TaskSubscription;
import io.zeebe.util.collection.CompactList;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.processor.StreamProcessorController;
import io.zeebe.servicecontainer.ServiceName;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.transport.RemoteAddress;
import io.zeebe.transport.TransportListener;
import io.zeebe.util.DeferredCommandContext;
import io.zeebe.util.actor.Actor;
import io.zeebe.util.allocation.HeapBufferAllocator;
import io.zeebe.util.buffer.BufferUtil;
import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;

public class TaskSubscriptionManager implements Actor, TransportListener
{
    protected static final String NAME = "taskqueue.subscription.manager";
    public static final int NUM_CONCURRENT_REQUESTS = 1_024;

    protected final ServiceStartContext serviceContext;
    protected final Function<DirectBuffer, LockTaskStreamProcessor> streamProcessorSupplier;

    protected final Map<DirectBuffer, Int2ObjectHashMap<LogStreamBucket>> logStreamBuckets = new HashMap<>();
    protected final Long2ObjectHashMap<LockTaskStreamProcessor> streamProcessorBySubscriptionId = new Long2ObjectHashMap<>();

    protected final DeferredCommandContext asyncContext = new DeferredCommandContext(NUM_CONCURRENT_REQUESTS);

    /*
     * For credits handling, we use two datastructures here:
     *   * a one-to-one thread-safe ring buffer for ingestion of requests
     *   * a non-thread-safe list for requests that could not be successfully dispatched to the corresponding stream processor
     *
     * Note: we could also use a single data structure, if the thread-safe buffer allowed us to decide in the consuming
     *   handler whether we actually want to consume an item off of it; then, we could simply leave a request
     *   if it cannot be dispatched.
     *   afaik there is no such datastructure available out of the box, so we are going with two datastructures
     *   see also https://github.com/real-logic/Agrona/issues/96
     */
    protected final CreditsRequestBuffer creditRequestBuffer;
    protected final CompactList backPressuredCreditsRequests;
    protected final CreditsRequest creditsRequest = new CreditsRequest();

    protected long nextSubscriptionId = 0;



    public TaskSubscriptionManager(ServiceStartContext serviceContext)
    {
        this(serviceContext, taskType -> new LockTaskStreamProcessor(taskType));
    }

    public TaskSubscriptionManager(
            ServiceStartContext serviceContext,
            Function<DirectBuffer, LockTaskStreamProcessor> streamProcessorBuilder)
    {
        this.serviceContext = serviceContext;
        this.streamProcessorSupplier = streamProcessorBuilder;
        this.creditRequestBuffer = new CreditsRequestBuffer(
            NUM_CONCURRENT_REQUESTS,
            (r) ->
            {
                final boolean dispatched = dispatchSubscriptionCredits(r);
                if (!dispatched)
                {
                    backpressureRequest(r);
                }
            });
        this.backPressuredCreditsRequests = new CompactList(CreditsRequest.LENGTH, creditRequestBuffer.getCapacityUpperBound(), new HeapBufferAllocator());
    }

    @Override
    public String name()
    {
        return NAME;
    }

    @Override
    public int doWork() throws Exception
    {
        final int asyncWork = asyncContext.doWork();

        final int backpressuredWork = dispatchBackpressuredSubscriptionCredits();
        final int creditsRequests;
        if (backPressuredCreditsRequests.size() == 0)
        {
            // only accept new requests when backpressured ones have been processed
            // this is required to guarantee that backPressuredCreditsRequests won't overflow
            creditsRequests = creditRequestBuffer.handleRequests();
        }
        else
        {
            creditsRequests = 0;
        }

        return asyncWork + backpressuredWork + creditsRequests;
    }

    public CompletableFuture<Void> addSubscription(final TaskSubscription subscription)
    {
        return asyncContext.runAsync(future ->
        {
            ensureNotNull("subscription", subscription);

            final DirectBuffer taskType = subscription.getLockTaskType();

            ensureNotNull("lock task type", taskType);

            final DirectBuffer topicName = subscription.getTopicName();
            final int partitionId = subscription.getPartitionId();

            final LogStreamBucket logStreamBucket = getLogStreamBucket(topicName, partitionId);
            if (logStreamBucket == null)
            {
                final String errorMessage = String.format("Topic with name '%s' and partition id '%d' not found.", bufferAsString(topicName), partitionId);
                throw new RuntimeException(errorMessage);
            }

            final long subscriptionId = nextSubscriptionId++;
            subscription.setSubscriberKey(subscriptionId);

            final LockTaskStreamProcessor streamProcessor = logStreamBucket.getStreamProcessorByTaskType(taskType);
            if (streamProcessor != null)
            {
                streamProcessorBySubscriptionId.put(subscriptionId, streamProcessor);

                streamProcessor
                    .addSubscription(subscription)
                    .handle((r, t) -> t == null ? future.complete(null) : future.completeExceptionally(t));
            }
            else
            {
                createStreamProcessorService(logStreamBucket, taskType)
                    .thenCompose(processor ->
                    {
                        streamProcessorBySubscriptionId.put(subscriptionId, processor);

                        logStreamBucket.addStreamProcessor(processor);

                        return processor.addSubscription(subscription);
                    })
                    .handle((r, t) -> t == null ? future.complete(null) : future.completeExceptionally(t));
            }
        });
    }

    protected CompletableFuture<LockTaskStreamProcessor> createStreamProcessorService(final LogStreamBucket logStreamBucket, final DirectBuffer taskType)
    {
        final CompletableFuture<LockTaskStreamProcessor> future = new CompletableFuture<>();

        final ServiceName<LogStream> logStreamServiceName = logStreamBucket.getLogServiceName();

        final String logName = logStreamBucket.getLogStream().getLogName();
        final ServiceName<StreamProcessorController> streamProcessorServiceName = taskQueueLockStreamProcessorServiceName(logName, bufferAsString(taskType));
        final String streamProcessorName = streamProcessorServiceName.getName();

        // need to copy the type buffer because it is not durable
        final DirectBuffer newTaskTypeBuffer = cloneBuffer(taskType);

        final LockTaskStreamProcessor streamProcessor = streamProcessorSupplier.apply(newTaskTypeBuffer);
        final StreamProcessorService streamProcessorService = new StreamProcessorService(
                streamProcessorName,
                TASK_LOCK_STREAM_PROCESSOR_ID,
                streamProcessor)
            .eventFilter(LockTaskStreamProcessor.eventFilter())
            .reprocessingEventFilter(LockTaskStreamProcessor.reprocessingEventFilter(newTaskTypeBuffer));

        serviceContext.createService(streamProcessorServiceName, streamProcessorService)
            .dependency(logStreamServiceName, streamProcessorService.getSourceStreamInjector())
            .dependency(logStreamServiceName, streamProcessorService.getTargetStreamInjector())
            .dependency(SNAPSHOT_STORAGE_SERVICE, streamProcessorService.getSnapshotStorageInjector())
            .dependency(ACTOR_SCHEDULER_SERVICE, streamProcessorService.getActorSchedulerInjector())
            .dependency(COUNTERS_MANAGER_SERVICE, streamProcessorService.getCountersInjector())
            .install()
            .handle((r, t) -> t == null ? future.complete(streamProcessor) : future.completeExceptionally(t));

        return future;
    }

    public CompletableFuture<Void> removeSubscription(long subscriptionId)
    {
        return asyncContext.runAsync(future ->
        {
            final LockTaskStreamProcessor streamProcessor = streamProcessorBySubscriptionId.remove(subscriptionId);
            if (streamProcessor != null)
            {
                streamProcessor
                    .removeSubscription(subscriptionId)
                    .thenCompose(hasSubscriptions -> !hasSubscriptions ? removeStreamProcessorService(streamProcessor) : CompletableFuture.completedFuture(null))
                    .handle((r, t) -> t == null ? future.complete(null) : future.completeExceptionally(t));
            }
            else
            {
                future.complete(null);
            }
        });
    }

    protected CompletionStage<Void> removeStreamProcessorService(final LockTaskStreamProcessor streamProcessor)
    {
        final LogStreamBucket logStreamBucket = getLogStreamBucket(streamProcessor.getLogStreamTopicName(), streamProcessor.getLogStreamPartitionId());

        logStreamBucket.removeStreamProcessor(streamProcessor);

        final String logName = logStreamBucket.getLogStream().getLogName();
        final String taskType = bufferAsString(streamProcessor.getSubscriptedTaskType());
        final ServiceName<StreamProcessorController> streamProcessorServiceName = taskQueueLockStreamProcessorServiceName(logName, taskType);

        return serviceContext.removeService(streamProcessorServiceName);
    }

    public boolean increaseSubscriptionCreditsAsync(CreditsRequest request)
    {
        return creditRequestBuffer.offerRequest(request);
    }

    /**
     * @param request
     * @return if request was handled
     */
    protected boolean dispatchSubscriptionCredits(CreditsRequest request)
    {
        final LockTaskStreamProcessor streamProcessor = streamProcessorBySubscriptionId.get(request.getSubscriberKey());

        if (streamProcessor != null)
        {
            return streamProcessor.increaseSubscriptionCreditsAsync(request);
        }
        else
        {
            // ignore
            return true;
        }
    }

    protected void backpressureRequest(CreditsRequest request)
    {
        request.appendTo(backPressuredCreditsRequests);
    }

    protected int dispatchBackpressuredSubscriptionCredits()
    {
        int nextRequestToConsume = backPressuredCreditsRequests.size() - 1;
        int numSuccessfulRequests = 0;

        while (nextRequestToConsume >= 0)
        {
            creditsRequest.wrapListElement(backPressuredCreditsRequests, nextRequestToConsume);
            final boolean success = dispatchSubscriptionCredits(creditsRequest);

            if (success)
            {
                backPressuredCreditsRequests.remove(nextRequestToConsume);
                numSuccessfulRequests++;
                nextRequestToConsume--;
            }
            else
            {
                break;
            }
        }

        return numSuccessfulRequests;
    }

    public void addStream(LogStream logStream, ServiceName<LogStream> logStreamServiceName)
    {
        asyncContext.runAsync(future ->
        {
            logStreamBuckets
                .computeIfAbsent(logStream.getTopicName(), k -> new Int2ObjectHashMap<>())
                .put(logStream.getPartitionId(), new LogStreamBucket(logStream, logStreamServiceName));
        });
    }

    public void removeStream(LogStream logStream)
    {
        asyncContext.runAsync(future ->
        {
            final DirectBuffer topicName = logStream.getTopicName();
            final int partitionId = logStream.getPartitionId();

            final Int2ObjectHashMap<LogStreamBucket> partitions = logStreamBuckets.get(topicName);

            if (partitions != null)
            {
                partitions.remove(partitionId);

                if (partitions.isEmpty())
                {
                    logStreamBuckets.remove(topicName);
                }
            }

            removeSubscriptionsForLogStream(topicName, partitionId);
        });
    }

    protected void removeSubscriptionsForLogStream(DirectBuffer topicName, final int partitionId)
    {
        final Set<Entry<Long, LockTaskStreamProcessor>> entrySet = streamProcessorBySubscriptionId.entrySet();
        for (Entry<Long, LockTaskStreamProcessor> entry : entrySet)
        {
            final LockTaskStreamProcessor streamProcessor = entry.getValue();
            if (topicName.equals(streamProcessor.getLogStreamTopicName()) && partitionId == streamProcessor.getLogStreamPartitionId())
            {
                entrySet.remove(entry);
            }
        }
    }

    public void onClientChannelCloseAsync(int channelId)
    {
        asyncContext.runAsync(() ->
        {
            final Iterator<LockTaskStreamProcessor> processorIt = streamProcessorBySubscriptionId.values().iterator();
            while (processorIt.hasNext())
            {
                final LockTaskStreamProcessor processor = processorIt.next();
                processor
                    .onClientChannelCloseAsync(channelId)
                    .thenCompose(hasSubscriptions -> !hasSubscriptions ? removeStreamProcessorService(processor) : CompletableFuture.completedFuture(null));
            }
        });
    }

    public int getCreditRequestCapacityUpperBound()
    {
        return creditRequestBuffer.getCapacityUpperBound();
    }

    protected LogStreamBucket getLogStreamBucket(final DirectBuffer topicName, final int partitionId)
    {
        final Int2ObjectHashMap<LogStreamBucket> partitions = logStreamBuckets.get(topicName);

        if (partitions != null)
        {
            return partitions.get(partitionId);
        }

        return null;
    }

    static class LogStreamBucket
    {
        protected final LogStream logStream;
        protected final ServiceName<LogStream> logStreamServiceName;

        protected List<LockTaskStreamProcessor> streamProcessors = new ArrayList<>();

        LogStreamBucket(LogStream logStream, ServiceName<LogStream> logStreamServiceName)
        {
            this.logStream = logStream;
            this.logStreamServiceName = logStreamServiceName;
        }

        public LogStream getLogStream()
        {
            return logStream;
        }

        public ServiceName<LogStream> getLogServiceName()
        {
            return logStreamServiceName;
        }

        public LockTaskStreamProcessor getStreamProcessorByTaskType(DirectBuffer taskType)
        {
            LockTaskStreamProcessor streamProcessorForType = null;

            final int size = streamProcessors.size();
            int current = 0;

            while (current < size && streamProcessorForType == null)
            {
                final LockTaskStreamProcessor streamProcessor = streamProcessors.get(current);

                if (BufferUtil.equals(taskType, streamProcessor.getSubscriptedTaskType()))
                {
                    streamProcessorForType = streamProcessor;
                }

                current += 1;
            }

            return streamProcessorForType;
        }

        public void addStreamProcessor(LockTaskStreamProcessor streamProcessor)
        {
            streamProcessors.add(streamProcessor);
        }

        public void removeStreamProcessor(LockTaskStreamProcessor streamProcessor)
        {
            streamProcessors.remove(streamProcessor);
        }
    }

    @Override
    public void onConnectionEstablished(RemoteAddress remoteAddress)
    {
    }

    @Override
    public void onConnectionClosed(RemoteAddress remoteAddress)
    {
        onClientChannelCloseAsync(remoteAddress.getStreamId());
    }

}
