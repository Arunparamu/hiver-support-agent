package com.hiver.supportagent.service;

import com.hiver.supportagent.data.DataStore;
import com.hiver.supportagent.model.Dtos.RetrievedExample;
import com.hiver.supportagent.model.HistoricalConversation;
import com.hiver.supportagent.util.TfIdfVectorizer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Retrieves the most similar historically-resolved conversations for a new
 * customer message. This is what "grounds" the reply draft in how the brand
 * has actually handled similar issues before, rather than letting the LLM
 * free-associate a policy that may not match the brand's real resolution
 * pattern (refund windows, DM redirect for PII, etc).
 *
 * Optionally restricts the candidate pool to conversations of the same
 * predicted intent, which meaningfully improves retrieval precision (see
 * report/REPORT.md ablation note).
 */
@Service
public class RetrievalService {

    private final DataStore dataStore;
    private TfIdfVectorizer vectorizer;
    private List<HistoricalConversation> corpus;

    public RetrievalService(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @PostConstruct
    public void init() {
        corpus = dataStore.getHistoricalConversations();
        vectorizer = new TfIdfVectorizer();
        List<String> texts = corpus.stream().map(HistoricalConversation::getCustomerText).toList();
        vectorizer.fit(texts);
    }

    public List<RetrievedExample> retrieveTopK(String message, String predictedIntent, int k) {
        Map<String, Double> queryVec = vectorizer.vectorize(message);
        List<Map<String, Double>> docVecs = vectorizer.getDocVectors();

        List<ScoredDoc> scored = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            HistoricalConversation hc = corpus.get(i);
            double sim = TfIdfVectorizer.cosineSimilarity(queryVec, docVecs.get(i));
            // small boost for same-intent matches: retrieval-then-rerank by
            // intent agreement, rather than a hard filter, so we don't zero
            // out good matches when the classifier itself is uncertain.
            if (predictedIntent != null && predictedIntent.equals(hc.getIntent())) {
                sim += 0.15;
            }
            scored.add(new ScoredDoc(hc, sim));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<RetrievedExample> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            ScoredDoc sd = scored.get(i);
            out.add(new RetrievedExample(sd.doc.getCustomerText(), sd.doc.getBrandReply(), sd.doc.getIntent(), round3(sd.score)));
        }
        return out;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private record ScoredDoc(HistoricalConversation doc, double score) {}
}
