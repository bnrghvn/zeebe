/*
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
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
package io.zeebe.client.task.impl;

import io.zeebe.client.event.TaskEvent;
import io.zeebe.client.event.impl.EventImpl;
import io.zeebe.client.event.impl.TaskEventImpl;
import io.zeebe.client.impl.RequestManager;
import io.zeebe.client.impl.cmd.CommandImpl;
import io.zeebe.client.task.cmd.UpdateTaskRetriesCommand;
import io.zeebe.util.EnsureUtil;

public class UpdateRetriesCommandImpl extends CommandImpl<TaskEvent> implements UpdateTaskRetriesCommand
{

    protected final TaskEventImpl taskEvent;

    public UpdateRetriesCommandImpl(RequestManager client, TaskEvent baseEvent)
    {
        super(client);
        EnsureUtil.ensureNotNull("base event", baseEvent);
        this.taskEvent = new TaskEventImpl((TaskEventImpl) baseEvent, TaskEventType.UPDATE_RETRIES.name());
    }

    @Override
    public UpdateTaskRetriesCommand retries(int remainingRetries)
    {
        this.taskEvent.setRetries(remainingRetries);
        return this;
    }

    @Override
    public EventImpl getEvent()
    {
        return taskEvent;
    }

    @Override
    public String getExpectedStatus()
    {
        return TaskEventType.RETRIES_UPDATED.name();
    }

}
