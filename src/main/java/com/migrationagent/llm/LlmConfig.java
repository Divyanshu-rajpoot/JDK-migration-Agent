package com.migrationagent.llm;

/**
 * Configuration for connecting to an LLM provider.
 *
 * <p>Supports multiple providers via a unified interface:</p>
 * <ul>
 *   <li><b>OLLAMA</b> — Local models via Ollama (http://localhost:11434)</li>
 *   <li><b>LLAMACPP</b> — Local models via llama.cpp server (http://localhost:8080)</li>
 *   <li><b>OPENAI</b> — OpenAI API (gpt-4, gpt-3.5-turbo, etc.)</li>
 *   <li><b>OPENAI_COMPATIBLE</b> — Any OpenAI-compatible API (LM Studio, vLLM, etc.)</li>
 *   <li><b>GITHUB_COPILOT</b> — GitHub Copilot via API token</li>
 *   <li><b>NONE</b> — Rule-based only (no AI)</li>
 * </ul>
 */
public class LlmConfig {

    public enum Provider {
        NONE,
        OLLAMA,
        LLAMACPP,
        OPENAI,
        OPENAI_COMPATIBLE,
        GITHUB_COPILOT
    }

    private final Provider provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    private LlmConfig(Builder b) {
        this.provider = b.provider;
        this.baseUrl = b.baseUrl;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.temperature = b.temperature;
        this.maxTokens = b.maxTokens;
        this.timeoutSeconds = b.timeoutSeconds;
    }

    public Provider getProvider() { return provider; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public boolean isEnabled() { return provider != Provider.NONE; }

    /**
     * Create default config for each provider (user can override via CLI).
     */
    public static LlmConfig forProvider(Provider provider, String model, String apiKey) {
        return switch (provider) {
            case OLLAMA -> builder()
                    .provider(Provider.OLLAMA)
                    .baseUrl("http://localhost:11434")
                    .model(model != null ? model : "codellama:13b")
                    .build();
            case LLAMACPP -> builder()
                    .provider(Provider.LLAMACPP)
                    .baseUrl("http://localhost:8080")
                    .model(model != null ? model : "default")
                    .build();
            case OPENAI -> builder()
                    .provider(Provider.OPENAI)
                    .baseUrl("https://api.openai.com")
                    .apiKey(apiKey)
                    .model(model != null ? model : "gpt-4o")
                    .build();
            case OPENAI_COMPATIBLE -> builder()
                    .provider(Provider.OPENAI_COMPATIBLE)
                    .baseUrl("http://localhost:1234") // LM Studio default
                    .model(model != null ? model : "default")
                    .build();
            case GITHUB_COPILOT -> builder()
                    .provider(Provider.GITHUB_COPILOT)
                    .baseUrl("https://api.githubcopilot.com")
                    .apiKey(apiKey)
                    .model(model != null ? model : "gpt-4o")
                    .build();
            case NONE -> builder().provider(Provider.NONE).build();
        };
    }

    @Override
    public String toString() {
        return String.format("LlmConfig[%s, url=%s, model=%s]", provider, baseUrl, model);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Provider provider = Provider.NONE;
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private double temperature = 0.1; // Low temp for code generation
        private int maxTokens = 4096;
        private int timeoutSeconds = 60;

        public Builder provider(Provider p) { this.provider = p; return this; }
        public Builder baseUrl(String u) { this.baseUrl = u; return this; }
        public Builder apiKey(String k) { this.apiKey = k != null ? k : ""; return this; }
        public Builder model(String m) { this.model = m; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }
        public Builder maxTokens(int m) { this.maxTokens = m; return this; }
        public Builder timeoutSeconds(int t) { this.timeoutSeconds = t; return this; }

        public LlmConfig build() { return new LlmConfig(this); }
    }
}
