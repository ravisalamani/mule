/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.compatibility.transport.vm;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.activation.DataHandler;

import org.mule.compatibility.core.api.endpoint.EndpointURI;
import org.mule.compatibility.core.api.endpoint.OutboundEndpoint;
import org.mule.compatibility.core.api.transport.NoReceiverForEndpointException;
import org.mule.compatibility.core.transport.AbstractMessageDispatcher;
import org.mule.compatibility.transport.vm.i18n.VMMessages;
import org.mule.runtime.api.metadata.DataType;
import org.mule.runtime.core.DefaultMuleEvent;
import org.mule.runtime.core.DefaultMuleMessage;
import org.mule.runtime.core.api.MuleEvent;
import org.mule.runtime.core.api.MuleException;
import org.mule.runtime.core.api.MuleMessage;
import org.mule.runtime.core.api.MuleMessage.Builder;
import org.mule.runtime.core.api.MuleRuntimeException;
import org.mule.runtime.core.api.connector.DispatchException;
import org.mule.runtime.core.api.execution.ExecutionCallback;
import org.mule.runtime.core.api.execution.ExecutionTemplate;
import org.mule.runtime.core.config.i18n.CoreMessages;
import org.mule.runtime.core.execution.TransactionalExecutionTemplate;
import org.mule.runtime.core.util.queue.Queue;
import org.mule.runtime.core.util.queue.QueueSession;

/**
 * <code>VMMessageDispatcher</code> is used for providing in memory interaction between components.
 */
public class VMMessageDispatcher extends AbstractMessageDispatcher
{
    private final VMConnector connector;

    public VMMessageDispatcher(OutboundEndpoint endpoint)
    {
        super(endpoint);
        this.connector = (VMConnector) endpoint.getConnector();
    }

    @Override
    protected void doDispatch(final MuleEvent event) throws Exception
    {
        EndpointURI endpointUri = endpoint.getEndpointURI();

        if (endpointUri == null)
        {
            throw new DispatchException(CoreMessages.objectIsNull("Endpoint"), event, getEndpoint());
        }
        MuleEvent eventToDispatch = DefaultMuleEvent.copy(event);
        eventToDispatch.clearFlowVariables();
        eventToDispatch.setMessage(createInboundMessage(eventToDispatch.getMessage()));
        QueueSession session = getQueueSession();
        Queue queue = session.getQueue(endpointUri.getAddress());
        if (!queue.offer(eventToDispatch.getMessage(), connector.getQueueTimeout()))
        {
            // queue is full
            throw new DispatchException(VMMessages.queueIsFull(queue.getName(), queue.size()),
                eventToDispatch, getEndpoint());
        }
        if (logger.isDebugEnabled())
        {
            logger.debug("dispatched MuleEvent on endpointUri: " + endpointUri);
        }
    }

    private QueueSession getQueueSession() throws MuleException
    {
        return connector.getTransactionalResource(endpoint);
    }

    @Override
    protected MuleMessage doSend(final MuleEvent event) throws Exception
    {
        MuleMessage retMessage;
        final VMMessageReceiver receiver = connector.getReceiver(endpoint.getEndpointURI());
        // Apply any outbound transformers on this event before we dispatch

        if (receiver == null)
        {
            throw new NoReceiverForEndpointException(VMMessages.noReceiverForEndpoint(connector.getName(),
                endpoint.getEndpointURI()));
        }

        MuleEvent eventToSend = DefaultMuleEvent.copy(event);
        final MuleMessage message = createInboundMessage(eventToSend.getMessage());

        ExecutionTemplate<MuleMessage> executionTemplate = TransactionalExecutionTemplate.createTransactionalExecutionTemplate(
                event.getMuleContext(), receiver.getEndpoint().getTransactionConfig());
        ExecutionCallback<MuleMessage> processingCallback = () -> receiver.onCall(message);
        retMessage = executionTemplate.execute(processingCallback);

        if (logger.isDebugEnabled())
        {
            logger.debug("sent event on endpointUri: " + endpoint.getEndpointURI());
        }
        if (retMessage != null)
        {
            retMessage = createInboundMessage(retMessage);
        }
        return retMessage;
    }

    @Override
    protected void doDispose()
    {
        // template method
    }

    @Override
    protected void doConnect() throws Exception
    {
        if (!endpoint.getExchangePattern().hasResponse())
        {
            // use the default queue profile to configure this queue.
            connector.getQueueProfile().configureQueue(endpoint.getMuleContext(),
                endpoint.getEndpointURI().getAddress(), connector.getQueueManager());
        }
    }

    @Override
    protected void doDisconnect() throws Exception
    {
        // template method
    }

    public MuleMessage createInboundMessage(MuleMessage source) throws Exception
    {
        Object payload = source.getPayload();

        if (payload instanceof List
                && ((List) payload).stream().filter(item -> item instanceof DefaultMuleMessage).count() > 0)
        {
            List<Object> newListPayload = new ArrayList<>();
            for (Object item : (List) payload)
            {
                if (item instanceof MuleMessage)
                {
                    newListPayload.add(copyToInbound((MuleMessage) item, ((MuleMessage) item)
                            .getPayload()));
                }
                else
                {
                    newListPayload.add(item);
                }
            }
            payload = newListPayload;
        }
        return copyToInbound(source, payload);
    }

    /**
     * copy outbound artifacts to inbound artifacts in the new message
     */
    private MuleMessage copyToInbound(MuleMessage currentMessage, Object payload) throws Exception
    {
        final Builder newMessageBuiler = MuleMessage.builder(currentMessage).payload(payload);

        // Copy message, but put all outbound properties and attachments on inbound scope.
        // We ignore inbound and invocation scopes since the VM receiver needs to behave the
        // same way as any other receiver in Mule and would only receive inbound headers
        // and attachments
        currentMessage.getOutboundAttachmentNames().forEach(name ->
                newMessageBuiler.addInboundAttachment(name, currentMessage.getOutboundAttachment(name)));
        newMessageBuiler.clearOutboundAttachments();

        currentMessage.getOutboundPropertyNames().forEach(name ->
                newMessageBuiler.addInboundProperty(name, currentMessage.getOutboundProperty(name), currentMessage.getOutboundPropertyDataType(name)));
        newMessageBuiler.clearOutboundProperties();

        return newMessageBuiler.build();
    }

}
