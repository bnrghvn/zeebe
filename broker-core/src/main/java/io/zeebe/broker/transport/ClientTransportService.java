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
package io.zeebe.broker.transport;

import io.zeebe.broker.services.Counters;
import io.zeebe.dispatcher.Dispatcher;
import io.zeebe.servicecontainer.Injector;
import io.zeebe.servicecontainer.Service;
import io.zeebe.servicecontainer.ServiceStartContext;
import io.zeebe.servicecontainer.ServiceStopContext;
import io.zeebe.transport.ClientTransport;
import io.zeebe.transport.Transports;
import io.zeebe.util.actor.ActorScheduler;

public class ClientTransportService implements Service<ClientTransport>
{
    protected final Injector<ActorScheduler> schedulerInjector = new Injector<>();
    protected final Injector<Dispatcher> receiveBufferInjector = new Injector<>();
    protected final Injector<Dispatcher> sendBufferInjector = new Injector<>();
    protected final Injector<Counters> countersInjector = new Injector<>();
    protected final int requestPoolSize;

    protected ClientTransport transport;
    private String name;

    public ClientTransportService(int requestPoolSize, String name)
    {
        this.requestPoolSize = requestPoolSize;
        this.name = name;
    }

    @Override
    public void start(ServiceStartContext startContext)
    {
        final Counters counters = countersInjector.getValue();
        final Dispatcher receiveBuffer = receiveBufferInjector.getValue();
        final Dispatcher sendBuffer = sendBufferInjector.getValue();
        final ActorScheduler scheduler = schedulerInjector.getValue();

        transport = Transports.newClientTransport()
            .name(name)
            .countersManager(counters.getCountersManager())
            .messageReceiveBuffer(receiveBuffer)
            .sendBuffer(sendBuffer)
            .requestPoolSize(requestPoolSize)
            .scheduler(scheduler)
            .build();
    }

    @Override
    public void stop(ServiceStopContext stopContext)
    {
        stopContext.async(transport.closeAsync());
    }

    @Override
    public ClientTransport get()
    {
        return transport;
    }

    public Injector<Dispatcher> getSendBufferInjector()
    {
        return sendBufferInjector;
    }

    public Injector<Dispatcher> getReceiveBufferInjector()
    {
        return receiveBufferInjector;
    }

    public Injector<ActorScheduler> getSchedulerInjector()
    {
        return schedulerInjector;
    }

    public Injector<Counters> getCountersInjector()
    {
        return countersInjector;
    }

}
