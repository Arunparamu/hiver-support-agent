package com.hiver.supportagent.model;

import java.util.List;

/**
 * All request/response DTOs for the agent pipeline, grouped in one file for
 * brevity (each is a small static nested record).
 */
public class Dtos {

    public record ProcessRequest(String message) {}

    public record ClassificationResult(
            String intent,
            double confidence,
            String method,          // "llm" | "keyword_baseline"
            List<String> topIntents // ranked alternatives with scores, for transparency
    ) {}

    public record RetrievedExample(
            String customerText,
            String brandReply,
            String intent,
            double similarity
    ) {}

    public record ReplyDraft(
            String draftReply,
            String method,              // "llm_grounded" | "template_fallback"
            List<RetrievedExample> groundedOn
    ) {}

    public record EscalationDecision(
            boolean escalate,
            String reason,
            List<String> triggeredRules
    ) {}

    public record ProcessResponse(
            String message,
            ClassificationResult classification,
            ReplyDraft reply,
            EscalationDecision escalation
    ) {}

    public record JudgeScore(
            int relevance,     // 1-5
            int groundedness,  // 1-5
            int correctness,   // 1-5
            int tone,          // 1-5
            String rationale
    ) {}
}
