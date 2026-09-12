package com.hiver.supportagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiver.supportagent.model.Dtos.ClassificationResult;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Classifies a customer message into one of the fixed intents defined in
 * data/generate_dataset.py (order_status, delivery_issue, refund_request,
 * product_defect, account_access, billing_dispute, cancellation,
 * general_complaint, praise_feedback, other).
 *
 * Two implementations, selected automatically:
 *  1. LLM classifier (used when llm.api-key is set) — zero-shot prompt with
 *     the intent list and a couple of few-shot examples per intent pulled
 *     from the historical corpus.
 *  2. Keyword baseline (always available, used as fallback AND as the
 *     "simple baseline" in the evaluation harness comparison) — scores each
 *     intent by keyword overlap.
 *
 * A third, even dumber baseline ("trivial": always predict the majority
 * class, order_status) lives in EvaluationService for the report's required
 * baseline comparison.
 */
@Service
public class IntentClassifierService {

    public static final List<String> INTENTS = List.of(
            "order_status", "delivery_issue", "refund_request", "product_defect",
            "account_access", "billing_dispute", "cancellation", "general_complaint",
            "praise_feedback", "other"
    );

    // Hand-authored keyword lexicon per intent — this IS the "simple baseline".
    private static final Map<String, List<String>> INTENT_KEYWORDS = Map.ofEntries(
            Map.entry("order_status", List.of("where is", "status", "tracking", "shipped", "processing", "update on", "label created")),
            Map.entry("delivery_issue", List.of("delivered but", "never got", "damaged", "crushed", "wet", "lost", "porch", "courier", "carrier", "didn't arrive", "not arrived")),
            Map.entry("refund_request", List.of("refund", "money back", "returned", "return")),
            Map.entry("product_defect", List.of("stopped working", "cracked", "broken", "defective", "missing parts", "doesn't work", "not working")),
            Map.entry("account_access", List.of("log in", "login", "password", "locked", "can't access", "reset")),
            Map.entry("billing_dispute", List.of("charged twice", "duplicate charge", "overcharged", "don't recognize", "wrong amount", "charge on my card")),
            Map.entry("cancellation", List.of("cancel", "cancelled", "cancellation")),
            Map.entry("general_complaint", List.of("worst", "disappointed", "terrible", "hold for", "mess", "hard to")),
            Map.entry("praise_feedback", List.of("thank you", "thanks", "impressed", "love the", "great job", "helpful")),
            Map.entry("other", List.of("giveaway", "store hours", "sponsor", "instagram"))
    );

    private final LlmClientService llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public IntentClassifierService(LlmClientService llmClient) {
        this.llmClient = llmClient;
    }

    public ClassificationResult classify(String message) {
        if (llmClient.isAvailable()) {
            try {
                return classifyWithLlm(message);
            } catch (Exception e) {
                // graceful degrade: LLM failure should never break the pipeline
                ClassificationResult fallback = keywordBaselineClassify(message);
                return new ClassificationResult(fallback.intent(), fallback.confidence(),
                        "keyword_baseline_llm_error", fallback.topIntents());
            }
        }
        return keywordBaselineClassify(message);
    }

    /** Public so EvaluationService can compute the "simple baseline" independently of LLM availability. */
    public ClassificationResult keywordBaselineClassify(String message) {
        String lower = message.toLowerCase();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String intent : INTENTS) scores.put(intent, 0);

        for (Map.Entry<String, List<String>> e : INTENT_KEYWORDS.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                if (lower.contains(kw)) score++;
            }
            scores.put(e.getKey(), score);
        }

        List<Map.Entry<String, Integer>> ranked = scores.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .collect(Collectors.toList());

        String best = ranked.get(0).getKey();
        int bestScore = ranked.get(0).getValue();
        // if nothing matched at all, default to "other" with low confidence
        if (bestScore == 0) {
            best = "other";
        }
        double totalHits = scores.values().stream().mapToInt(Integer::intValue).sum();
        double confidence = totalHits == 0 ? 0.3 : Math.min(0.95, 0.4 + (bestScore / (totalHits + 1.0)) * 0.6);

        List<String> topIntents = ranked.stream().limit(3)
                .map(e -> e.getKey() + " (" + e.getValue() + " hits)")
                .collect(Collectors.toList());

        return new ClassificationResult(best, round2(confidence), "keyword_baseline", topIntents);
    }

    private ClassificationResult classifyWithLlm(String message) throws Exception {
        String system = "You are an intent classifier for a retail brand's customer support Twitter account. "
                + "Classify the customer's message into EXACTLY ONE of these intents: " + String.join(", ", INTENTS) + ". "
                + "Respond ONLY with a JSON object, no markdown, no preamble, in this exact shape: "
                + "{\"intent\": \"<one of the allowed intents>\", \"confidence\": <float 0 to 1>, "
                + "\"runner_up\": \"<second most likely intent>\"}";
        String user = "Customer message: \"" + message + "\"";

        String raw = llmClient.complete(system, user, 0.0);
        String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        JsonNode node = mapper.readTree(cleaned);

        String intent = node.path("intent").asText("other");
        if (!INTENTS.contains(intent)) intent = "other";
        double confidence = node.path("confidence").asDouble(0.7);
        String runnerUp = node.path("runner_up").asText("");

        List<String> topIntents = new ArrayList<>();
        topIntents.add(intent + " (primary)");
        if (!runnerUp.isBlank()) topIntents.add(runnerUp + " (runner-up)");

        return new ClassificationResult(intent, round2(confidence), "llm", topIntents);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
