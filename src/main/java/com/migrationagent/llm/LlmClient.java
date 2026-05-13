package com.migrationagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Unified LLM client that talks to any provider via their HTTP API.
 *
 * <p>All providers expose an OpenAI-compatible chat completions endpoint,
 * or we adapt to their native format (Ollama uses /api/generate).</p>
 *
 * <h3>Supported providers:</h3>
 * <ul>
 *   <li><b>Ollama</b>: POST /api/generate (native) or /v1/chat/completions (OpenAI compat)</li>
 *   <li><b>llama.cpp</b>: POST /completion</li>
 *   <li><b>OpenAI</b>: POST /v1/chat/completions</li>
 *   <li><b>OpenAI-compatible</b>: POST /v1/chat/completions (LM Studio, vLLM, etc.)</li>
 *   <li><b>GitHub Copilot</b>: POST /v1/chat/completions with Copilot token</li>
 * </ul>
 */
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LlmConfig config;
    private final HttpClient httpClient;

    public LlmClient(LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
    }

    /**
     * Check if LLM is enabled and reachable.
     */
    public boolean isAvailable() {
        if (!config.isEnabled()) return false;

        try {
            String healthUrl = switch (config.getProvider()) {
                case OLLAMA -> config.getBaseUrl() + "/api/tags";
                case LLAMACPP -> config.getBaseUrl() + "/health";
                default -> null; // Cloud APIs — assume available if key is set
            };

            if (healthUrl == null) {
                return config.getApiKey() != null && !config.getApiKey().isBlank();
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean available = response.statusCode() == 200;
            log.info("LLM health check: {} — {}", healthUrl, available ? "OK" : "FAILED");
            return available;

        } catch (Exception e) {
            log.warn("LLM not available: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send a prompt to the LLM and get a response.
     *
     * @param systemPrompt the system instructions (role of the AI)
     * @param userPrompt   the user's question/context
     * @return the LLM's text response, or null if failed
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!config.isEnabled()) return null;

        try {
            return switch (config.getProvider()) {
                case OLLAMA -> callOllama(systemPrompt, userPrompt);
                case LLAMACPP -> callLlamaCpp(systemPrompt, userPrompt);
                case OPENAI, OPENAI_COMPATIBLE, GITHUB_COPILOT ->
                        callOpenAiCompatible(systemPrompt, userPrompt);
                default -> null;
            };
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Ollama — POST /api/generate
    // ═══════════════════════════════════════════════════════════════════

    private String callOllama(String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String url = config.getBaseUrl() + "/api/generate";

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("prompt", systemPrompt + "\n\n" + userPrompt);
        body.put("stream", false);

        ObjectNode options = mapper.createObjectNode();
        options.put("temperature", config.getTemperature());
        options.put("num_predict", config.getMaxTokens());
        body.set("options", options);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        log.debug("Ollama request: model={}", config.getModel());
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("Ollama error {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode json = mapper.readTree(response.body());
        return json.path("response").asText(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  llama.cpp — POST /completion
    // ═══════════════════════════════════════════════════════════════════

    private String callLlamaCpp(String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String url = config.getBaseUrl() + "/completion";

        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", "<|system|>\n" + systemPrompt + "\n<|user|>\n" + userPrompt + "\n<|assistant|>\n");
        body.put("n_predict", config.getMaxTokens());
        body.put("temperature", config.getTemperature());
        body.put("stop", "\n<|");
        body.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        log.debug("llama.cpp request to {}", url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("llama.cpp error {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode json = mapper.readTree(response.body());
        return json.path("content").asText(null);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  OpenAI-compatible — POST /v1/chat/completions
    //  Works with: OpenAI, LM Studio, vLLM, GitHub Copilot, etc.
    // ═══════════════════════════════════════════════════════════════════

    private String callOpenAiCompatible(String systemPrompt, String userPrompt)
            throws IOException, InterruptedException {
        String url = config.getBaseUrl() + "/v1/chat/completions";

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("temperature", config.getTemperature());
        body.put("max_tokens", config.getMaxTokens());

        ArrayNode messages = mapper.createArrayNode();

        ObjectNode sysMsg = mapper.createObjectNode();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);

        body.set("messages", messages);

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));

        // Add auth header if API key is present
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }

        // GitHub Copilot uses a different auth header
        if (config.getProvider() == LlmConfig.Provider.GITHUB_COPILOT) {
            reqBuilder.header("Authorization", "token " + config.getApiKey());
            reqBuilder.header("Editor-Version", "migration-agent/1.0.0");
        }

        HttpRequest request = reqBuilder.build();

        log.debug("OpenAI-compatible request: model={}, url={}", config.getModel(), url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.error("API error {}: {}", response.statusCode(), response.body());
            return null;
        }

        JsonNode json = mapper.readTree(response.body());
        JsonNode choices = json.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("message").path("content").asText(null);
        }

        return null;
    }
}
