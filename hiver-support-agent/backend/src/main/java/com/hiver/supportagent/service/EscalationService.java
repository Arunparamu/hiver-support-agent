package com.hiver.supportagent.service;

import com.hiver.supportagent.model.Dtos.ClassificationResult;
import com.hiver.supportagent.model.Dtos.EscalationDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Decides auto-handle vs escalate-to-human, with a stated reason.
 *
 * DESIGN CHOICE: this is deliberately rule-based, not LLM-judged. Escalation
 * is a safety-critical / trust-critical decision (false negatives here mean
 * a legal threat or a self-harm signal gets an automated reply). Rules are
 * auditable, cheap, fast, and don't depend on model availability — an LLM
 * classifier's mistakes are relatively low-stakes (wrong intent bucket),
 * but an escalation miss is high-stakes. See DECISION_LOG.md.
 *
 * The rules run REGARDLESS of which intent was predicted — a billing
 * dispute that also contains a legal threat must escalate even if the
 * classifier only saw "billing_dispute".
 */
@Service
public class EscalationService {

    @Value("${agent.min-classifier-confidence}")
    private double minConfidence;

    @Value("${agent.escalate-keywords}")
    private String escalateKeywordsRaw;

    @Value("${agent.anger-keywords}")
    private String angerKeywordsRaw;

    private static final List<String> ALWAYS_AUTO_INTENTS = List.of("praise_feedback");
    private static final List<String> USUALLY_ESCALATE_INTENTS = List.of("billing_dispute", "general_complaint");

    public EscalationDecision decide(String message, ClassificationResult classification) {
        List<String> triggered = new ArrayList<>();
        String lower = message.toLowerCase();

        List<String> escalateKeywords = Arrays.asList(escalateKeywordsRaw.split(","));
        List<String> angerKeywords = Arrays.asList(angerKeywordsRaw.split(","));

        boolean hasSafetyOrLegal = escalateKeywords.stream().anyMatch(kw -> lower.contains(kw.trim()));
        if (hasSafetyOrLegal) triggered.add("safety_or_legal_keyword_detected");

        boolean hasAngerSignal = angerKeywords.stream().anyMatch(kw -> lower.contains(kw.trim()));
        if (hasAngerSignal) triggered.add("high_frustration_language");

        boolean lowConfidence = classification.confidence() < minConfidence;
        if (lowConfidence) triggered.add("classifier_confidence_below_threshold(" + classification.confidence() + "<" + minConfidence + ")");

        boolean multiIntentSignal = countLikelyIntentSignals(lower) >= 2;
        if (multiIntentSignal) triggered.add("possible_multi_intent_message");

        boolean intentUsuallyEscalates = USUALLY_ESCALATE_INTENTS.contains(classification.intent());
        if (intentUsuallyEscalates) triggered.add("intent_category_requires_human(" + classification.intent() + ")");

        boolean intentAlwaysAuto = ALWAYS_AUTO_INTENTS.contains(classification.intent());

        boolean escalate;
        String reason;

        if (hasSafetyOrLegal) {
            escalate = true;
            reason = "Message contains language suggesting legal/safety risk (e.g. legal threat, fraud claim, or self-harm signal). Always escalate regardless of intent or confidence.";
        } else if (intentAlwaysAuto && !hasAngerSignal) {
            escalate = false;
            reason = "Intent '" + classification.intent() + "' is low-risk and does not require case-specific account action.";
        } else if (lowConfidence) {
            escalate = true;
            reason = "Classifier confidence (" + classification.confidence() + ") is below the auto-handle threshold (" + minConfidence + "); uncertain intent should not be auto-resolved.";
        } else if (multiIntentSignal) {
            escalate = true;
            reason = "Message appears to reference multiple distinct issues; single-intent auto-reply risks addressing only one.";
        } else if (intentUsuallyEscalates || hasAngerSignal) {
            escalate = true;
            reason = "Intent '" + classification.intent() + "' or detected frustration language typically requires a human's judgment call (e.g. discretionary refund, account-specific dispute).";
        } else {
            escalate = false;
            reason = "Intent '" + classification.intent() + "' matches a well-covered, low-risk resolution pattern with sufficient classifier confidence (" + classification.confidence() + ").";
        }

        return new EscalationDecision(escalate, reason, triggered);
    }

    /** Very crude multi-intent heuristic: counts distinct intent keyword families present. */
    private int countLikelyIntentSignals(String lower) {
        String[][] families = {
                {"refund", "money back"},
                {"charged twice", "duplicate charge", "overcharged"},
                {"never arrived", "never got", "lost", "damaged"},
                {"cancel"},
                {"password", "log in", "login", "locked"}
        };
        int count = 0;
        for (String[] fam : families) {
            for (String kw : fam) {
                if (lower.contains(kw)) { count++; break; }
            }
        }
        return count;
    }
}
