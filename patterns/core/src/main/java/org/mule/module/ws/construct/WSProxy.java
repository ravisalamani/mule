/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.module.ws.construct;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mule.MessageExchangePattern;
import org.mule.api.MessagingException;
import org.mule.api.MuleContext;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.api.construct.FlowConstructInvalidException;
import org.mule.api.endpoint.InboundEndpoint;
import org.mule.api.endpoint.OutboundEndpoint;
import org.mule.api.processor.MessageProcessor;
import org.mule.api.processor.MessageProcessorChainBuilder;
import org.mule.api.source.MessageSource;
import org.mule.config.i18n.MessageFactory;
import org.mule.construct.AbstractConfigurationPattern;
import org.mule.endpoint.DynamicOutboundEndpoint;
import org.mule.pattern.core.construct.CopyInboundToOutboundPropertiesTransformerCallback;
import org.mule.transformer.TransformerTemplate;
import org.mule.util.ObjectUtils;
import org.mule.util.StringUtils;

/**
 * This class is implemented to act as a Proxy for a Web Service. It listens for requests on the inbound endpoint and if
 * it encounters the "WSDL" property in the address, it will fetch the WSDL from the original web service and return it
 * back. In case the wsdlFile property is set, when the WSProxyService encounters a request for the wsdl, instead of
 * fetching the WSDL from the original web service, it will return back the file expressed in the property. When a
 * normal SOAP request is encountered, it will forward the call to the web service with no modifications to the SOAP
 * message. The outbound router of this class must include the address of the webservice to be proxied. No need to
 * include the method name as a parameter in the address, since it will be in the SOAP message as well. Furthermore a
 * property named uriWsdl can optionally be set which as the name suggests, indicate the URL of the WSDL for the
 * service. If this property is not set, the address of the WSDL will be assumed to be the value of uriWebservice
 * followed by "?WSDL".
 */
public class WSProxy extends AbstractConfigurationPattern
{
    private final AbstractProxyRequestProcessor proxyMessageProcessor;
    private final OutboundEndpoint outboundEndpoint;

    public WSProxy(final String name,
                   final MuleContext muleContext,
                   final MessageSource messageSource,
                   final OutboundEndpoint outboundEndpoint,
                   final List<MessageProcessor> transformers,
                   final List<MessageProcessor> responseTransformers) throws MuleException
    {
        this(name, muleContext, messageSource, outboundEndpoint, transformers, responseTransformers,
            new DynamicWsdlProxyRequestProcessor(outboundEndpoint));
    }

    public WSProxy(final String name,
                   final MuleContext muleContext,
                   final MessageSource messageSource,
                   final OutboundEndpoint outboundEndpoint,
                   final List<MessageProcessor> transformers,
                   final List<MessageProcessor> responseTransformers,
                   final String wsdlContents) throws MuleException
    {
        this(name, muleContext, messageSource, outboundEndpoint, transformers, responseTransformers,
            new StaticWsdlProxyRequestProcessor(wsdlContents));
    }

    public WSProxy(final String name,
                   final MuleContext muleContext,
                   final MessageSource messageSource,
                   final OutboundEndpoint outboundEndpoint,
                   final List<MessageProcessor> transformers,
                   final List<MessageProcessor> responseTransformers,
                   final URI wsdlUri) throws MuleException
    {
        this(name, muleContext, messageSource, outboundEndpoint, transformers, responseTransformers,
            new DynamicWsdlProxyRequestProcessor(wsdlUri));
    }

    private WSProxy(final String name,
                    final MuleContext muleContext,
                    final MessageSource messageSource,
                    final OutboundEndpoint outboundEndpoint,
                    final List<MessageProcessor> transformers,
                    final List<MessageProcessor> responseTransformers,
                    final AbstractProxyRequestProcessor proxyMessageProcessor) throws MuleException
    {
        super(name, muleContext, transformers, responseTransformers);

        if (messageSource == null)
        {
            throw new FlowConstructInvalidException(
                MessageFactory.createStaticMessage("messageSource can't be null on: " + this.toString()),
                this);
        }

        super.setMessageSource(messageSource);

        if (outboundEndpoint == null)
        {
            throw new FlowConstructInvalidException(
                MessageFactory.createStaticMessage("outboundEndpoint can't be null on: " + this.toString()),
                this);
        }

        this.outboundEndpoint = outboundEndpoint;

        this.proxyMessageProcessor = proxyMessageProcessor;
    }

    @Override
    protected void configureMessageProcessorsBeforeTransformation(final MessageProcessorChainBuilder builder)
    {
        builder.chain(proxyMessageProcessor);
    }

    @Override
    protected void configureMessageProcessorsAfterTransformation(final MessageProcessorChainBuilder builder)
    {
        // FIXME (DDO) MULE-5502 ensure inbound/outbound content length is correct when a transformer is defined on the
        // proxy

        builder.chain(new TransformerTemplate(new CopyInboundToOutboundPropertiesTransformerCallback()));
        builder.chain(outboundEndpoint);
    }

    @Override
    protected void validateConstruct() throws FlowConstructInvalidException
    {
        super.validateConstruct();

        if ((messageSource instanceof InboundEndpoint)
            && (!((InboundEndpoint) messageSource).getExchangePattern().equals(
                MessageExchangePattern.REQUEST_RESPONSE)))
        {
            throw new FlowConstructInvalidException(
                MessageFactory.createStaticMessage("WSProxy only works with a request-response inbound endpoint."),
                this);
        }

        if (!outboundEndpoint.getExchangePattern().equals(MessageExchangePattern.REQUEST_RESPONSE))
        {
            throw new FlowConstructInvalidException(
                MessageFactory.createStaticMessage("WSProxy only works with a request-response outbound endpoint."),
                this);
        }
    }

    private static abstract class AbstractProxyRequestProcessor implements MessageProcessor
    {
        private static final String HTTP_REQUEST = "http.request";
        private static final String WSDL_PARAM_1 = "?wsdl";
        private static final String WSDL_PARAM_2 = "&wsdl";

        protected final Log logger = LogFactory.getLog(WSProxy.class);

        public MuleEvent process(final MuleEvent event) throws MuleException
        {
            if (isWsdlRequest(event))
            {
                return buildWsdlResult(event);
            }

            if (logger.isDebugEnabled())
            {
                logger.debug("Forwarding SOAP message");
            }

            return event;
        }

        private MuleEvent buildWsdlResult(final MuleEvent event) throws MuleException
        {
            try
            {
                final String wsdlContents = getWsdlContents(event);
                event.getMessage().setPayload(wsdlContents);

                // the processing is stopped so that the result is not passed through
                // the outbound router but will be passed back as a result
                event.setStopFurtherProcessing(true);
                return event;
            }
            catch (final Exception e)
            {
                throw new MessagingException(
                    MessageFactory.createStaticMessage("Impossible to retrieve WSDL for proxied service"),
                    event, e);
            }
        }

        private boolean isWsdlRequest(final MuleEvent event) throws MuleException
        {
            // retrieve the original HTTP request. This will be used to check if the
            // user asked for the WSDL or a service method.
            final String httpRequest = event.getMessage().<String> getInboundProperty(HTTP_REQUEST);

            if (httpRequest == null)
            {
                logger.warn("WS Proxy can't rewrite WSDL for non-HTTP " + event);
                return false;
            }

            final String lowerHttpRequest = httpRequest.toLowerCase();

            // check if the inbound request contains the WSDL parameter
            return (lowerHttpRequest.indexOf(WSDL_PARAM_1) != -1)
                   || (lowerHttpRequest.indexOf(WSDL_PARAM_2) != -1);
        }

        protected abstract String getWsdlContents(MuleEvent event) throws Exception;
    }

    private static class StaticWsdlProxyRequestProcessor extends AbstractProxyRequestProcessor
    {
        private final String wsdlContents;

        /**
         * Instantiates a request processor that returns a static WSDL contents when the proxy receives a WSDL request.
         * 
         * @param wsdlContents the WSDL contents to use.
         * @throws FlowConstructInvalidException
         */
        StaticWsdlProxyRequestProcessor(final String wsdlContents) throws FlowConstructInvalidException
        {
            if (StringUtils.isBlank(wsdlContents))
            {
                throw new FlowConstructInvalidException(
                    MessageFactory.createStaticMessage("wsdlContents can't be empty"));
            }

            this.wsdlContents = wsdlContents;
        }

        @Override
        protected String getWsdlContents(final MuleEvent event) throws Exception
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Serving static WSDL");
            }

            return wsdlContents;
        }
    }

    private static class DynamicWsdlProxyRequestProcessor extends AbstractProxyRequestProcessor
    {
        private interface WsdlAddressProvider
        {
            String get(MuleEvent event);
        }

        private static final String LOCALHOST = "localhost";
        private final WsdlAddressProvider wsdlAddressProvider;

        /**
         * Instantiates a request processor that fetches and rewrites addresses of a remote WSDL when the proxy receives
         * a WSDL request.
         * 
         * @param wsdlUri the URI to fetch the WSDL from.
         * @throws FlowConstructInvalidException
         */
        DynamicWsdlProxyRequestProcessor(final URI wsdlUri) throws FlowConstructInvalidException
        {
            if (wsdlUri == null)
            {
                throw new FlowConstructInvalidException(
                    MessageFactory.createStaticMessage("wsdlUri can't be null"));
            }

            final String wsdlAddress = wsdlUri.toString();

            wsdlAddressProvider = new WsdlAddressProvider()
            {
                public String get(final MuleEvent event)
                {
                    return wsdlAddress;
                }
            };

            logger.info("Using url " + wsdlAddress + " as WSDL");
        }

        /**
         * Instantiates a request processor that fetches and rewrites addresses of a remote WSDL when the proxy receives
         * a WSDL request.
         * 
         * @param outboundEndpoint the endpoint to fetch the WSDL from.
         * @throws FlowConstructInvalidException
         */
        DynamicWsdlProxyRequestProcessor(final OutboundEndpoint outboundEndpoint)
            throws FlowConstructInvalidException
        {
            if (outboundEndpoint == null)
            {
                throw new FlowConstructInvalidException(
                    MessageFactory.createStaticMessage("outboundEndpoint can't be null"));
            }

            final String wsAddress = outboundEndpoint.getAddress();

            if (outboundEndpoint instanceof DynamicOutboundEndpoint)
            {
                wsdlAddressProvider = new WsdlAddressProvider()
                {
                    public String get(final MuleEvent event)
                    {
                        final String resolvedWsAddress = event.getMuleContext()
                            .getExpressionManager()
                            .parse(wsAddress, event.getMessage(), true);

                        return makeWsdlAddress(resolvedWsAddress);
                    }
                };

                logger.info("Using dynamic WSDL with service address: " + wsAddress);
            }
            else
            {
                final String wsdlAddress = makeWsdlAddress(wsAddress);

                wsdlAddressProvider = new WsdlAddressProvider()
                {
                    public String get(final MuleEvent event)
                    {
                        return wsdlAddress;
                    }
                };

                logger.info("Setting WSDL address to: " + wsdlAddress);
            }
        }

        private static String makeWsdlAddress(final String wsAddress)
        {
            return StringUtils.substringBefore(wsAddress, "?").concat("?wsdl");
        }

        @Override
        protected String getWsdlContents(final MuleEvent event) throws Exception
        {
            final String wsdlAddress = wsdlAddressProvider.get(event);
            String wsdlString;

            final MuleContext muleContext = event.getMuleContext();
            final InboundEndpoint webServiceEndpoint = muleContext.getEndpointFactory().getInboundEndpoint(
                wsdlAddress);

            if (logger.isDebugEnabled())
            {
                logger.debug("Retrieving WSDL from web service with: " + webServiceEndpoint);
            }

            final MuleMessage replyWSDL = webServiceEndpoint.request(event.getTimeout());
            wsdlString = replyWSDL.getPayloadAsString();

            // create a new mule message with the new WSDL
            final String realWsdlAddress = wsdlAddress.split("\\?")[0];
            final String proxyWsdlAddress = event.getEndpoint().getEndpointURI().getUri().toString();
            wsdlString = wsdlString.replaceAll(realWsdlAddress, proxyWsdlAddress);

            if (wsdlString.indexOf(LOCALHOST) > -1)
            {
                wsdlString = wsdlString.replaceAll(LOCALHOST, InetAddress.getLocalHost().getHostName());
            }

            if (logger.isDebugEnabled())
            {
                logger.debug("WSDL retrieved successfully");
            }

            return wsdlString;
        }
    }

    @Override
    public String toString()
    {
        return ObjectUtils.toString(this);
    }

    @Override
    public String getConstructType()
    {
        return "Web-Service-Proxy";
    }
}
