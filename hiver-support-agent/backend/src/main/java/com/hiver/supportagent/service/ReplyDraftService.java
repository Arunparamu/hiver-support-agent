package com.hiver.supportagent.service;

import com.hiver.supportagent.data.DataStore;
import com.hiver.supportagent.model.Dtos.ReplyDraft;
import com.hiver.supportagent.model.Dtos.RetrievedExample;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Drafts a reply grounded in the top-k retrieved historical resolutions.
 *
 * LLM path: retrieved examples are given as few-shot grounding context and
 * the model is instructed to match the brand's voice/policy patterns
 * (short, apologetic, redirect PII to DM) rather than invent new policy.
 *
 * Fallback path (no LLM key): adapts the single best-matching historical
 * reply almost verbatim, swapping in the new order number if present. This
 * is deliberately "dumb" — it is the honest floor of what's achievable with
 * zero model calls, and it's what the evaluation harness uses to show the
 * LLM's lift over pure retrieval (see report/REPORT.md baselines section).
 */
@Service
public class ReplyDraftService {

    private final LlmClientService llmClient;
    private final RetrievalService retrievalService;
    private final DataStore dataStore;

    public ReplyDraftService(LlmClientService llmClient, RetrievalService retrievalService, DataStore dataStore) {
        this.llmClient = llmClient;
        this.retrievalService = retrievalService;
        this.dataStore = dataStore;
    }

    public ReplyDraft draft(String message, String predictedIntent) {
        List<RetrievedExample> grounded = retrievalService.retrieveTopK(message, predictedIntent, 3);

        if (llmClient.isAvailable()) {
            try {
                String reply = draftWithLlm(message, predictedIntent, grounded);
                return new ReplyDraft(reply.trim(), "llm_grounded", grounded);
            } catch (Exception e) {
                // fall through to template fallback below
            }
        }
        String reply = templateFallback(message, grounded);
        return new ReplyDraft(reply, "template_fallback", grounded);
    }

    private String draftWithLlm(String message, String predictedIntent, List<RetrievedExample> grounded) throws Exception {
        String brand = dataStore.getBrandName();
        StringBuilder examples = new StringBuilder();
        for (RetrievedExample ex : grounded) {
            examples.append("- Customer said: \"").append(ex.customerText()).append("\"\n")
                    .append("  ").append(brand).append(" replied: \"").append(ex.brandReply()).append("\"\n");
        }

        String system = "You are drafting a reply as the Twitter support account @" + brand + ". "
                + "Write ONE short reply (under 280 characters, Twitter-style) in the same voice as the "
                + "example resolutions below: brief, empathetic, never invent order-specific facts you don't "
                + "have, and redirect anything involving PII (order numbers, emails, addresses, card digits) "
                + "to DM. Do not promise specific refund amounts or dates you cannot verify. "
                + "Ground your reply in the patterns shown in these real past resolutions for intent '"
                + predictedIntent + "':\n" + examples
                + "\nRespond with ONLY the reply text, no quotes, no preamble.";
        String user = "Customer message: \"" + message + "\"";

        return llmClient.complete(system, user, 0.4);
    }

    private String templateFallback(String message, List<RetrievedExample> grounded) {
        if (grounded.isEmpty()) {
            return "Thanks for reaching out! Could you DM us more details so we can look into this for you? ^Support";
        }
        // adapt the single best match's brand reply as-is; this is intentionally
        // "dumb" retrieval-only behavior, see class javadoc.
        return grounded.get(0).brandReply();
    }
}
