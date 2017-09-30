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
package io.zeebe.broker.logstreams.processor;

import io.zeebe.broker.services.Counters;
import io.zeebe.logstreams.LogStreams;
import io.zeebe.logstreams.log.LogStream;
import io.zeebe.logstreams.log.LoggedEvent;
import io.zeebe.logstreams.processor.*;
import io.zeebe.logstreams.spi.SnapshotPositionProvider;
import io.zeebe.logstreams.spi.SnapshotStorage;
import io.zeebe.protocol.Protocol;
import io.zeebe.protocol.impl.BrokerEventMetadata;
import io.zeebe.servicecontainer.*;
import io.zeebe.util.actor.ActorScheduler;

public class StreamProcessorService implements Service<StreamProcessorController>
{
    private final Injector<LogStream> sourceStreamInjector = new Injector<>();
    private final Injector<LogStream> targetStreamInjector = new Injector<>();
    private final Injector<SnapshotStorage> snapshotStorageInjector = new Injector<>();
    private final Injector<ActorScheduler> actorSchedulerInjector = new Injector<>();
    private final Injector<Counters> countersInjector = new Injector<>();

    private final String name;
    private final int id;
    private final StreamProcessor streamProcessor;

    protected MetadataFilter customEventFilter;
    protected EventFilter customReprocessingEventFilter;
    protected boolean readOnly;
    protected StreamProcessorErrorHandler errorHandler;

    protected final MetadataFilter versionFilter = (m) ->
    {
        if (m.getProtocolVersion() > Protocol.PROTOCOL_VERSION)
        {
            throw new RuntimeException(String.format("Cannot handle event with version newer " +
                    "than what is implemented by broker (%d > %d)", m.getProtocolVersion(), Protocol.PROTOCOL_VERSION));
        }

        return true;
    };


    protected SnapshotPositionProvider snapshotPositionProvider;

    private StreamProcessorController streamProcessorController;

    public StreamProcessorService(String name, int id, StreamProcessor streamProcessor)
    {
        this.name = name;
        this.id = id;
        this.streamProcessor = streamProcessor;
    }

    public StreamProcessorService eventFilter(MetadataFilter eventFilter)
    {
        this.customEventFilter = eventFilter;
        return this;
    }

    public StreamProcessorService reprocessingEventFilter(EventFilter reprocessingEventFilter)
    {
        this.customReprocessingEventFilter = reprocessingEventFilter;
        return this;
    }

    public StreamProcessorService readOnly(boolean readOnly)
    {
        this.readOnly = readOnly;
        return this;
    }

    public StreamProcessorService snapshotPositionProvider(SnapshotPositionProvider snapshotPositionProvider)
    {
        this.snapshotPositionProvider = snapshotPositionProvider;
        return this;
    }

    public StreamProcessorService errorHandler(StreamProcessorErrorHandler errorHandler)
    {
        this.errorHandler = errorHandler;
        return this;
    }

    @Override
    public void start(ServiceStartContext ctx)
    {
        final Counters counters = countersInjector.getValue();

        final LogStream sourceStream = sourceStreamInjector.getValue();
        final LogStream targetStream = targetStreamInjector.getValue();

        final SnapshotStorage snapshotStorage = snapshotStorageInjector.getValue();

        final ActorScheduler actorScheduler = actorSchedulerInjector.getValue();

        MetadataFilter metadataFilter = versionFilter;
        if (customEventFilter != null)
        {
            metadataFilter = metadataFilter.and(customEventFilter);
        }
        final EventFilter eventFilter = new MetadataEventFilter(metadataFilter);

        EventFilter reprocessingEventFilter = new MetadataEventFilter(versionFilter);
        if (customReprocessingEventFilter != null)
        {
            reprocessingEventFilter = reprocessingEventFilter.and(customReprocessingEventFilter);
        }

        if (errorHandler == null)
        {
            errorHandler = new DefaultStreamProcessorErrorHandler();
        }

        streamProcessorController = LogStreams.createStreamProcessor(name, id, streamProcessor)
            .sourceStream(sourceStream)
            .targetStream(targetStream)
            .snapshotStorage(snapshotStorage)
            .snapshotPositionProvider(snapshotPositionProvider)
            .actorScheduler(actorScheduler)
            .eventFilter(eventFilter)
            .reprocessingEventFilter(reprocessingEventFilter)
            .errorHandler(errorHandler)
            .readOnly(readOnly)
            .countersManager(counters.getCountersManager())
            .build();

        ctx.async(streamProcessorController.openAsync());
    }

    @Override
    public StreamProcessorController get()
    {
        return streamProcessorController;
    }

    @Override
    public void stop(ServiceStopContext ctx)
    {
        ctx.async(streamProcessorController.closeAsync());
    }

    public Injector<SnapshotStorage> getSnapshotStorageInjector()
    {
        return snapshotStorageInjector;
    }

    public Injector<ActorScheduler> getActorSchedulerInjector()
    {
        return actorSchedulerInjector;
    }

    public Injector<LogStream> getSourceStreamInjector()
    {
        return sourceStreamInjector;
    }

    public Injector<LogStream> getTargetStreamInjector()
    {
        return targetStreamInjector;
    }

    public StreamProcessorController getStreamProcessorController()
    {
        return streamProcessorController;
    }

    public Injector<Counters> getCountersInjector()
    {
        return countersInjector;
    }

    protected static class MetadataEventFilter implements EventFilter
    {

        protected final BrokerEventMetadata metadata = new BrokerEventMetadata();
        protected final MetadataFilter metadataFilter;

        public MetadataEventFilter(MetadataFilter metadataFilter)
        {
            this.metadataFilter = metadataFilter;
        }

        @Override
        public boolean applies(LoggedEvent event)
        {
            event.readMetadata(metadata);
            return metadataFilter.applies(metadata);
        }

    }

    protected static class DefaultStreamProcessorErrorHandler implements StreamProcessorErrorHandler
    {
        @Override
        public boolean canHandle(Exception error)
        {
            return false;
        }

        @Override
        public boolean onError(LoggedEvent failedEvent, Exception error)
        {
            return false;
        }
    }

}
