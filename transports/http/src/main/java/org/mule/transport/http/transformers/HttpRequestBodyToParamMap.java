/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.transport.http.transformers;

import org.mule.api.MuleMessage;
import org.mule.api.transformer.TransformerException;
import org.mule.transformer.AbstractMessageTransformer;
import org.mule.transformer.types.DataTypeFactory;
import org.mule.transport.http.HttpConstants;
import org.mule.util.StringUtils;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HttpRequestBodyToParamMap extends AbstractMessageTransformer
{
    
    public HttpRequestBodyToParamMap()
    {
        registerSourceType(DataTypeFactory.OBJECT);
        setReturnDataType(DataTypeFactory.OBJECT);
    }

    @Override
    public Object transformMessage(MuleMessage message, String encoding) throws TransformerException
    {
        Map<String, Object> paramMap = new HashMap<String, Object>();

        try
        {
            String httpMethod = message.getInboundProperty("http.method");
            String contentType = message.getInboundProperty("Content-Type");
            
            boolean isGet = HttpConstants.METHOD_GET.equalsIgnoreCase(httpMethod);
            boolean isPost = HttpConstants.METHOD_POST.equalsIgnoreCase(httpMethod);
            boolean isUrlEncoded = false;
            if (contentType != null)
            {
                isUrlEncoded = contentType.startsWith("application/x-www-form-urlencoded");
            }

            if (!(isGet || (isPost && isUrlEncoded)))
            {
                throw new Exception("The HTTP method or content type is unsupported!");
            }

            String queryString = null;
            if (isGet)
            {
                URI uri = new URI(message.getPayloadAsString(encoding));
                queryString = uri.getRawQuery();
            }
            else if (isPost)
            {
                queryString = new String(message.getPayloadAsBytes());
            }

            if (StringUtils.isNotBlank(queryString))
            {
                String[] pairs = queryString.split("&");
                for (String pair : pairs)
                {
                    String[] nameValue = pair.split("=");
                    if (nameValue.length == 2)
                    {
                        String key = URLDecoder.decode(nameValue[0], encoding);
                        String value = URLDecoder.decode(nameValue[1], encoding);
                        addToParameterMap(paramMap, key, value);
                    }
                }
            }
        }
        catch (Exception e)
        {
            throw new TransformerException(this, e);
        }

        return paramMap;
    }

    @SuppressWarnings("unchecked")
    private void addToParameterMap(Map<String, Object> paramMap, String key, String value)
    {
        Object existingValue = paramMap.get(key);
        if (existingValue != null)
        {
            List<Object> values;
            if (existingValue instanceof List<?>)
            {
                values = (List<Object>) existingValue;
            }
            else
            {
                values = Arrays.asList(existingValue);
            }

            values.add(value);
            paramMap.put(key, values);
        }
        else
        {
            paramMap.put(key, value);
        }
    }

    @Override
    public boolean isAcceptNull()
    {
        return false;
    }

}
