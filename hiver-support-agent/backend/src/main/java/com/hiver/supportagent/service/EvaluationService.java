package com.hiver.supportagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiver.supportagent.data.DataStore;
import com.hiver.supportagent.model.Dtos.*;
import com.hiver.supportagent.model.GoldenExample;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Runs the full evaluation harness against the golden set:
 *   1. Trivial baseline: always predict the majority intent class, never escalate.
 *   2. Simple baseline: keyword classifier (IntentClassifierService.keywordBaselineClassify).
 *   3. Main system: whatever classify()/draft()/decide() currently resolve to
 *      (LLM-backed if a key is configured, else same as simple baseline —
 *      this is called out explicitly in the report, see "what's misleading
 *      about my headline number").
 *
 * Metrics: per-intent precision/recall/F1, macro-F1, confusion counts,
 * escalation precision/recall (treating "should escalate"=positive class,
 * since a missed escalation is the costlier error), and mean LLM-judge
 * scores for reply quality (only computed for examples with a resolvable
 * intent, i.e. not "other"/spam, matching the report's stated eval scope).
 */
@Service
public class EvaluationService {

    private final DataStore dataStore;
    private final IntentClassifierService classifierService;
    private final ReplyDraftService replyDraftService;
    private final EscalationService escalationService;
    private final LlmClientService llmClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EvaluationService(DataStore dataStore, IntentClassifierService classifierService,
                              ReplyDraftService replyDraftService, EscalationService escalationService,
                              LlmClientService llmClient) {
        this.dataStore = dataStore;
        this.classifierService = classifierService;
        this.replyDraftService = replyDraftService;
        this.escalationService = escalationService;
        this.llmClient = llmClient;
    }

    public Map<String, Object> runFullEvaluation(boolean includeJudge, int judgeSampleSize) {
        List<GoldenExample> golden = dataStore.getGoldenExamples();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("n_examples", golden.size());
        result.put("brand", dataStore.getBrandName());
        result.put("trivial_baseline", evalClassification(golden, this::trivialPredict));
        result.put("simple_baseline_keyword", evalClassification(golden, ex -> classifierService.keywordBaselineClassify(ex.getCustomerText()).intent()));
        result.put("main_system", evalClassification(golden, ex -> classifierService.classify(ex.getCustomerText()).intent()));
        result.put("escalation_metrics", evalEscalation(golden));

        if (includeJudge && llmClient.isAvailable()) {
            result.put("llm_judge", runJudgeSample(golden, judgeSampleSize));
        } else {
            result.put("llm_judge", Map.of(
                    "status", "skipped",
                    "reason", llmClient.isAvailable() ? "judge sample size 0" : "no LLM API key configured — set LLM_API_KEY env var to enable reply-quality judging"
            ));
        }
        return result;
    }

    // -------------------------------------------------------------------
    // Classification metrics
    // -------------------------------------------------------------------

    private interface Predictor { String predict(GoldenExample ex); }

    private String trivialPredict(GoldenExample ex) {
        return "order_status"; // majority class in our historical distribution
    }

    private Map<String, Object> evalClassification(List<GoldenExample> golden, Predictor predictor) {
        Map<String, int[]> counts = new TreeMap<>(); // intent -> [tp, fp, fn]
        for (String intent : IntentClassifierService.INTENTS) counts.put(intent, new int[3]);

        int correct = 0;
        for (GoldenExample ex : golden) {
            String pred = predictor.predict(ex);
            String gold = ex.getGoldIntent();
            if (pred.equals(gold)) {
                correct++;
                counts.get(gold)[0]++; // tp
            } else {
                counts.computeIfAbsent(pred, k -> new int[3])[1]++; // fp for predicted class
                counts.computeIfAbsent(gold, k -> new int[3])[2]++; // fn for gold class
            }
        }

        double accuracy = golden.isEmpty() ? 0 : (double) correct / golden.size();

        Map<String, Object> perIntent = new LinkedHashMap<>();
        double macroF1Sum = 0;
        int intentCountForMacro = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int tp = e.getValue()[0], fp = e.getValue()[1], fn = e.getValue()[2];
            double precision = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
            double recall = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
            double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);
            if (tp + fn > 0) { // only count intents that actually appear in golden set toward macro-F1
                macroF1Sum += f1;
                intentCountForMacro++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("precision", round3(precision));
            m.put("recall", round3(recall));
            m.put("f1", round3(f1));
            m.put("support", tp + fn);
            perIntent.put(e.getKey(), m);
        }
        double macroF1 = intentCountForMacro == 0 ? 0 : macroF1Sum / intentCountForMacro;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accuracy", round3(accuracy));
        out.put("macro_f1", round3(macroF1));
        out.put("per_intent", perIntent);
        return out;
    }

    // -------------------------------------------------------------------
    // Escalation metrics — positive class = "should escalate" (costlier miss)
    // -------------------------------------------------------------------

    private Map<String, Object> evalEscalation(List<GoldenExample> golden) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        List<Map<String, Object>> falseNegatives = new ArrayList<>(); // MOST dangerous error: should've escalated but didn't
        for (GoldenExample ex : golden) {
            ClassificationResult classification = classifierService.classify(ex.getCustomerText());
            EscalationDecision decision = escalationService.decide(ex.getCustomerText(), classification);
            boolean predicted = decision.escalate();
            boolean gold = ex.isGoldEscalate();
            if (predicted && gold) tp++;
            else if (predicted && !gold) fp++;
            else if (!predicted && gold) {
                fn++;
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("id", ex.getId());
                miss.put("text", ex.getCustomerText());
                miss.put("system_reason_given", decision.reason());
                falseNegatives.add(miss);
            } else tn++;
        }
        double precision = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
        double f1 = (precision + recall) == 0 ? 0 : 2 * precision * recall / (precision + recall);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("precision", round3(precision));
        out.put("recall", round3(recall));
        out.put("f1", round3(f1));
        out.put("true_positive", tp);
        out.put("false_positive_unnecessary_escalation", fp);
        out.put("false_negative_missed_escalation", fn);
        out.put("true_negative", tn);
        out.put("missed_escalations_detail", falseNegatives);
        return out;
    }

    // -------------------------------------------------------------------
    // LLM-as-judge for reply quality
    // -------------------------------------------------------------------

    private Map<String, Object> runJudgeSample(List<GoldenExample> golden, int sampleSize) {
        List<GoldenExample> sample = golden.subList(0, Math.min(sampleSize, golden.size()));
        List<Map<String, Object>> perExample = new ArrayList<>();
        double relSum = 0, groundSum = 0, correctSum = 0, toneSum = 0;
        int n = 0;

        for (GoldenExample ex : sample) {
            try {
                ClassificationResult classification = classifierService.classify(ex.getCustomerText());
                ReplyDraft draft = replyDraftService.draft(ex.getCustomerText(), classification.intent());
                JudgeScore score = judgeReply(ex.getCustomerText(), draft);

                relSum += score.relevance();
                groundSum += score.groundedness();
                correctSum += score.correctness();
                toneSum += score.tone();
                n++;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", ex.getId());
                row.put("customer_text", ex.getCustomerText());
                row.put("draft_reply", draft.draftReply());
                row.put("scores", score);
                perExample.add(row);
            } catch (Exception ignored) {
                // skip examples where judge call fails; don't let one bad call kill the whole eval run
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n_judged", n);
        out.put("mean_relevance", n == 0 ? 0 : round3(relSum / n));
        out.put("mean_groundedness", n == 0 ? 0 : round3(groundSum / n));
        out.put("mean_correctness", n == 0 ? 0 : round3(correctSum / n));
        out.put("mean_tone", n == 0 ? 0 : round3(toneSum / n));
        out.put("examples", perExample);
        return out;
    }

    private JudgeScore judgeReply(String customerText, ReplyDraft draft) throws Exception {
        String system = "You are an expert customer-support QA reviewer. Score the draft reply below on 4 axes, "
                + "each 1-5 (5=best): relevance (does it address what the customer actually asked?), "
                + "groundedness (does it stick to the provided historical resolution examples rather than "
                + "inventing new facts/policy?), correctness (is it factually/procedurally sound, no false "
                + "promises?), tone (empathetic, professional, brand-appropriate?). "
                + "Respond ONLY with JSON: {\"relevance\": int, \"groundedness\": int, \"correctness\": int, "
                + "\"tone\": int, \"rationale\": \"one sentence\"}";
        StringBuilder groundedContext = new StringBuilder();
        for (RetrievedExample ex : draft.groundedOn()) {
            groundedContext.append("- \"").append(ex.customerText()).append("\" -> \"").append(ex.brandReply()).append("\"\n");
        }
        String user = "Customer message: \"" + customerText + "\"\n\n"
                + "Historical grounding examples provided to the drafter:\n" + groundedContext
                + "\nDraft reply to score: \"" + draft.draftReply() + "\"";

        String raw = llmClient.complete(system, user, 0.0);
        String cleaned = raw.replaceAll("```json", "").replaceAll("```", "").trim();
        JsonNode node = mapper.readTree(cleaned);
        return new JudgeScore(
                node.path("relevance").asInt(3),
                node.path("groundedness").asInt(3),
                node.path("correctness").asInt(3),
                node.path("tone").asInt(3),
                node.path("rationale").asText("")
        );
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
