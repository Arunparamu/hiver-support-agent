package com.hiver.supportagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Thin wrapper around an OpenAI-compatible /chat/completions endpoint (also
 * works with Anthropic's OpenAI-compatibility layer, or any local server that
 * speaks the same schema, e.g. Ollama/vLLM with an OpenAI-compatible route).
 *
 * DESIGN CHOICE: if llm.api-key is unset, isAvailable() returns false and
 * every caller (classifier, drafter, judge) falls back to a deterministic,
 * non-LLM implementation. This is what makes "reproduce results in <15 min"
 * possible without requiring the grader to have an API key — see README.
 */
@Service
public class LlmClientService {

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.model}")
    private String model;

    @Value("${llm.timeout-seconds}")
    private int timeoutSeconds;

    public LlmClientService() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Sends a single-turn chat completion request. Returns the raw text
     * content of the model's reply, or throws if the call fails — callers
     * are expected to catch and fall back.
     */
    public String complete(String systemPrompt, String userPrompt, double temperature) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("LLM not configured (llm.api-key is blank) — caller should use fallback path");
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        var messages = body.putArray("messages");
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        messages.add(sys);
        ObjectNode usr = mapper.createObjectNode();
        usr.put("role", "user");
        usr.put("content", userPrompt);
        messages.add(usr);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new RuntimeException("LLM call failed: HTTP " + response.statusCode() + " " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        return root.at("/choices/0/message/content").asText();
    }
}
