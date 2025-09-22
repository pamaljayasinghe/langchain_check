/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.apimgt.gateway.mediators;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.commons.json.JsonUtil;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.api.APIConstants;
import org.wso2.carbon.apimgt.api.gateway.ModelEndpointDTO;
import org.wso2.carbon.apimgt.gateway.internal.DataHolder;
import org.wso2.carbon.apimgt.gateway.utils.GatewayUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * TransformMediator extracts user request content, removes user-specified models,
 * and routes everything to hardcoded Mistral service through AIAPIMediator.
 */
public class TransformMediator extends AbstractMediator implements ManagedLifecycle {

    private static final Log log = LogFactory.getLog(TransformMediator.class);
    
    private String transformConfigs;
    private static final String HARDCODED_MODEL = "mistral-large-latest";

    /**
     * Sets the transform configuration JSON string (kept for compatibility).
     *
     * @param transformConfigs The configuration JSON string
     */
    public void setTransformConfigs(String transformConfigs) {
        this.transformConfigs = transformConfigs;
    }

    /**
     * Gets the transform configuration JSON string.
     *
     * @return The configuration JSON string
     */
    public String getTransformConfigs() {
        return transformConfigs;
    }

    @Override
    public void init(SynapseEnvironment synapseEnvironment) {
        if (log.isDebugEnabled()) {
            log.debug("TransformMediator: Initialized.");
        }
    }

    @Override
    public void destroy() {
        // Clean up resources if needed
    }

    /**
     * Mediates the message by extracting user request content, removing user-specified model,
     * and routing to hardcoded Mistral service through AIAPIMediator.
     *
     * @param messageContext The Synapse {@link MessageContext} to be processed.
     * @return {@code true} if mediation is successful, {@code false} if an error occurs
     */
    @Override
    public boolean mediate(MessageContext messageContext) {
        if (log.isDebugEnabled()) {
            log.debug("TransformMediator mediation started.");
        }

        try {
            // Initialize cache
            DataHolder.getInstance().initCache(GatewayUtils.getAPIKeyForEndpoints(messageContext));

            // Extract user request content
            String userRequestContent = extractUserRequestContent(messageContext);
            if (userRequestContent == null || userRequestContent.trim().isEmpty()) {
                log.warn("Unable to extract user request content");
                return true;
            }

            // Remove user-specified model from request and force Mistral
            removeUserModelFromRequest(messageContext);

            // Route to hardcoded Mistral service
            return routeToMistralService(messageContext, userRequestContent);

        } catch (Exception e) {
            log.error("Error in TransformMediator mediation", e);
            return false;
        }
    }

    /**
     * Removes user-specified model from the request payload and forces Mistral model.
     */
    private void removeUserModelFromRequest(MessageContext messageContext) {
        try {
            org.apache.axis2.context.MessageContext axis2MessageContext = 
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            if (JsonUtil.hasAJsonPayload(axis2MessageContext)) {
                String jsonPayload = JsonUtil.jsonPayloadToString(axis2MessageContext);
                if (jsonPayload != null) {
                    // Parse and modify the JSON to force our hardcoded model
                    Gson gson = new Gson();
                    Map<String, Object> payload = gson.fromJson(jsonPayload, Map.class);
                    
                    // Remove user-specified model and set our hardcoded model
                    payload.put("model", HARDCODED_MODEL);
                    
                    // Update the payload back to message context
                    String modifiedPayload = gson.toJson(payload);
                    JsonUtil.removeJsonPayload(axis2MessageContext);
                    JsonUtil.getNewJsonPayload(axis2MessageContext, modifiedPayload, true, true);
                    
                    if (log.isDebugEnabled()) {
                        log.debug("Replaced user model with hardcoded Mistral model: " + HARDCODED_MODEL);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error removing user model from request: " + e.getMessage());
        }
    }

    /**
     * Extracts user request content from the message payload.
     */
    private String extractUserRequestContent(MessageContext messageContext) {
        try {
            org.apache.axis2.context.MessageContext axis2MessageContext = 
                ((Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            RelayUtils.buildMessage(axis2MessageContext);

            if (JsonUtil.hasAJsonPayload(axis2MessageContext)) {
                String jsonPayload = JsonUtil.jsonPayloadToString(axis2MessageContext);
                return jsonPayload != null ? extractContentFromJsonPayload(jsonPayload) : null;
            }
            return null;
        } catch (Exception e) {
            log.warn("Error extracting user request content: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extracts content from JSON payload using a priority-based approach.
     */
    private String extractContentFromJsonPayload(String jsonPayload) {
        try {
            Gson gson = new Gson();
            Map<String, Object> payload = gson.fromJson(jsonPayload, Map.class);
            
            // Priority order: messages > prompt > input
            String[] keys = {"messages", "prompt", "input"};
            for (String key : keys) {
                if (payload.containsKey(key)) {
                    Object value = payload.get(key);
                    if ("messages".equals(key) && value instanceof java.util.List) {
                        return extractFromMessagesList((java.util.List<?>) value);
                    } else if (value != null) {
                        return value.toString();
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Error parsing JSON payload for content extraction: " + e.getMessage());
            return null;
        }
    }

    private String extractFromMessagesList(java.util.List<?> messages) {
        if (!messages.isEmpty()) {
            Object lastMessage = messages.get(messages.size() - 1);
            if (lastMessage instanceof Map) {
                Map<String, Object> messageMap = (Map<String, Object>) lastMessage;
                if (messageMap.containsKey("content")) {
                    return messageMap.get("content").toString();
                }
            }
        }
        return null;
    }

    /**
     * Routes the request to hardcoded Mistral service and sets up AIAPIMediator integration.
     */
    private boolean routeToMistralService(MessageContext messageContext, String userContent) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("Routing to Mistral service with user content: " + 
                         userContent.substring(0, Math.min(100, userContent.length())));
            }

            MistralService mistralService = new MistralService();
            
            // Check if Mistral service is available
            if (!mistralService.isServiceAvailable()) {
                log.warn("Mistral service is not available");
                messageContext.setProperty(APIConstants.AIAPIConstants.TARGET_ENDPOINT, 
                                         APIConstants.AIAPIConstants.REJECT_ENDPOINT);
                return true;
            }

            // Get full JSON response from Mistral instead of just parsed content
            String fullJsonResponse = mistralService.getFullJsonResponse(userContent);
            
            if (fullJsonResponse != null) {
                // Set up context for AIAPIMediator to process the actual Mistral response
                setupAIAPIMediatorIntegration(messageContext, fullJsonResponse, userContent);
                return true;
            } else {
                log.warn("No response received from Mistral service");
                return false;
            }

        } catch (Exception e) {
            log.error("Error routing to Mistral service", e);
            return false;
        }
    }

    /**
     * Sets up message context properties for AIAPIMediator integration.
     */
    private void setupAIAPIMediatorIntegration(MessageContext messageContext, String mistralResponse, String userContent) {
        ModelEndpointDTO mistralEndpoint = createMistralEndpoint();
        setLLMRouteConfigs(messageContext, mistralEndpoint);
        setSuccessStatus(messageContext);
        updateMessageBody(messageContext, mistralResponse);
        setDebugProperties(messageContext, mistralResponse, userContent);
        
        if (log.isDebugEnabled()) {
            log.debug("Set up AIAPIMediator integration for Mistral model: " + HARDCODED_MODEL);
        }
    }

    private ModelEndpointDTO createMistralEndpoint() {
        ModelEndpointDTO endpoint = new ModelEndpointDTO();
        endpoint.setModel(HARDCODED_MODEL);
        endpoint.setEndpointId("mistral_transform_endpoint");
        return endpoint;
    }

    private void setLLMRouteConfigs(MessageContext messageContext, ModelEndpointDTO endpoint) {
        Map<String, Object> llmRouteConfigs = new HashMap<>();
        llmRouteConfigs.put(APIConstants.AIAPIConstants.LLM_TARGET_MODEL_ENDPOINT, endpoint);
        llmRouteConfigs.put(APIConstants.AIAPIConstants.SUSPEND_DURATION, 0);
        
        messageContext.setProperty(APIConstants.AIAPIConstants.TARGET_ENDPOINT, endpoint.getEndpointId());
        messageContext.setProperty(APIConstants.AIAPIConstants.LLM_ROUTE_CONFIGS, llmRouteConfigs);
    }

    private void setSuccessStatus(MessageContext messageContext) {
        if (messageContext instanceof org.apache.synapse.core.axis2.Axis2MessageContext) {
            ((org.apache.synapse.core.axis2.Axis2MessageContext) messageContext).getAxis2MessageContext()
                    .setProperty(org.wso2.carbon.apimgt.gateway.APIMgtGatewayConstants.HTTP_SC, 200);
        }
    }

    private void updateMessageBody(MessageContext messageContext, String mistralResponse) {
        try {
            updateMessageBodyWithResponse(messageContext, mistralResponse);
            if (log.isDebugEnabled()) {
                log.debug("Updated message body with Mistral JSON response");
            }
        } catch (Exception e) {
            log.error("Failed to update message body with Mistral response", e);
        }
    }

    private void setDebugProperties(MessageContext messageContext, String mistralResponse, String userContent) {
        messageContext.setProperty("TRANSFORM_MISTRAL_RESPONSE", mistralResponse);
        messageContext.setProperty("TRANSFORM_USER_CONTENT", userContent);
        messageContext.setProperty("TRANSFORM_MODEL_USED", HARDCODED_MODEL);
    }

    /**
     * Updates the message body with the actual Mistral JSON response.
     * This passes through the real API response instead of creating a synthetic one.
     */
    private void updateMessageBodyWithResponse(MessageContext messageContext, String mistralJsonResponse) throws Exception {
        if (messageContext instanceof org.apache.synapse.core.axis2.Axis2MessageContext) {
            org.apache.axis2.context.MessageContext axis2MessageContext = 
                ((org.apache.synapse.core.axis2.Axis2MessageContext) messageContext).getAxis2MessageContext();
            
            // Update the message body with the actual Mistral JSON response (same as McpMediator)
            JsonUtil.removeJsonPayload(axis2MessageContext);
            JsonUtil.getNewJsonPayload(axis2MessageContext, mistralJsonResponse, true, true);
            
            // Set proper content type properties 
            axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.MESSAGE_TYPE, 
                    "application/json");
            axis2MessageContext.setProperty(org.apache.axis2.Constants.Configuration.CONTENT_TYPE, 
                    "application/json");
            
            if (log.isDebugEnabled()) {
                log.debug("Updated message body with actual Mistral JSON response");
            }
        }
    }

    @Override
    public boolean isContentAware() {
        return true; // We need to read the message content
    }
}
