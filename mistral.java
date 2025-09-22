package org.wso2.carbon.apimgt.gateway.mediators;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Service class for interacting with Mistral LLM API for request classification.
 */
public class MistralService {
    
    private static final Log log = LogFactory.getLog(MistralService.class);
    
    private static final String MISTRAL_API_URL = "https://api.mistral.ai/v1/chat/completions";
    private static final String MISTRAL_API_KEY = "#";
    private static final String MISTRAL_MODEL = "mistral-large-latest";

    public String classifyRequest(String prompt) {
        return executeWithErrorHandling(() -> executeRequest(createHttpClient(), createHttpRequest(prompt)));
    }

    public String classifyRequestWithSystemPrompt(String systemPrompt, String userPrompt) {
        return executeWithErrorHandling(() -> executeRequest(createHttpClient(), createHttpRequestWithSystemPrompt(systemPrompt, userPrompt)));
    }

    public boolean isServiceAvailable() {
        try {
            HttpClient httpClient = createHttpClient();
            HttpPost httpPost = createHealthCheckRequest();
            return executeHealthCheck(httpClient, httpPost);
        } catch (Exception e) {
            return false;
        }
    }

    private String executeWithErrorHandling(ThrowingSupplier<String> operation) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.warn("Error calling Mistral API: " + e.getMessage());
            return null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private HttpClient createHttpClient() {
        try {
            URL url = new URL(MISTRAL_API_URL);
            return APIUtil.getHttpClient(url.getPort(), url.getProtocol());
        } catch (Exception e) {
            log.warn("Error creating HTTP client: " + e.getMessage());
            return null;
        }
    }

    private HttpPost createHttpRequestWithPayload(String payload) {
        HttpPost httpPost = new HttpPost(MISTRAL_API_URL);
        httpPost.setHeader("Authorization", "Bearer " + MISTRAL_API_KEY);
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setEntity(new StringEntity(payload, StandardCharsets.UTF_8));
        return httpPost;
    }

    private HttpPost createHttpRequest(String prompt) {
        return createHttpRequestWithPayload(buildRequestPayload(prompt));
    }

    private HttpPost createHttpRequestWithSystemPrompt(String systemPrompt, String userPrompt) {
        return createHttpRequestWithPayload(buildRequestPayloadWithSystemPrompt(systemPrompt, userPrompt));
    }

    private HttpPost createHealthCheckRequest() {
        return createHttpRequestWithPayload(buildHealthCheckPayload());
    }

    private String buildRequestPayload(String prompt) {
        return buildPayload(0.1, null, createMessage("user", prompt));
    }

    private String buildRequestPayloadWithSystemPrompt(String systemPrompt, String userPrompt) {
        return buildPayload(0.1, null, createMessage("system", systemPrompt), createMessage("user", userPrompt));
    }

    private String buildHealthCheckPayload() {
        return buildPayload(null, 5, createMessage("user", "test"));
    }

    private String buildPayload(Double temperature, Integer maxTokens, JsonObject... messages) {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", MISTRAL_MODEL);
        if (temperature != null) requestBody.addProperty("temperature", temperature);
        if (maxTokens != null) requestBody.addProperty("max_tokens", maxTokens);
        requestBody.add("messages", new Gson().toJsonTree(messages));
        return requestBody.toString();
    }

    private JsonObject createMessage(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String executeRequest(HttpClient httpClient, HttpPost httpPost) throws IOException {
        String response = executeFullRequest(httpClient, httpPost);
        return response != null ? parseResponse(response) : null;
    }

    /**
     * Gets the full JSON response from Mistral without parsing, for when you want the complete API response.
     */
    public String getFullJsonResponse(String prompt) {
        return executeWithErrorHandling(() -> executeFullRequest(createHttpClient(), createHttpRequest(prompt)));
    }

    public String getFullJsonResponseWithSystemPrompt(String systemPrompt, String userPrompt) {
        return executeWithErrorHandling(() -> executeFullRequest(createHttpClient(), createHttpRequestWithSystemPrompt(systemPrompt, userPrompt)));
    }

    private String executeFullRequest(HttpClient httpClient, HttpPost httpPost) throws IOException {
        try (CloseableHttpResponse response = APIUtil.executeHTTPRequestWithRetries(httpPost, httpClient)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                return null;
            }
            
            // Return the full JSON response without parsing
            return EntityUtils.toString(response.getEntity());
        } catch (Exception e) {
            log.warn("Error executing HTTP request: " + e.getMessage());
            return null;
        }
    }

    private boolean executeHealthCheck(HttpClient httpClient, HttpPost httpPost) throws IOException {
        try (CloseableHttpResponse response = APIUtil.executeHTTPRequestWithRetries(httpPost, httpClient)) {
            return response.getStatusLine().getStatusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String parseResponse(String responseBody) {
        try {
            JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();
            if (jsonResponse.has("choices") && jsonResponse.getAsJsonArray("choices").size() > 0) {
                JsonObject choice = jsonResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message")) {
                    JsonObject messageObj = choice.getAsJsonObject("message");
                    if (messageObj.has("content")) {
                        return messageObj.get("content").getAsString().trim();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing Mistral response");
        }
        return null;
    }
}
