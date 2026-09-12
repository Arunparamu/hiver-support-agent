package com.hiver.supportagent.util;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Minimal from-scratch TF-IDF + cosine similarity implementation.
 *
 * Why hand-rolled instead of a library: keeps the backend dependency-light
 * (no heavyweight NLP/ML jar), fully deterministic, and easy to audit for a
 * take-home. It's intentionally simple (no stemming, no n-grams beyond
 * unigrams) — see report/REPORT.md failure analysis for where this hurts
 * retrieval quality (e.g. "cancel" vs "cancelled" treated as different tokens
 * is mitigated by a basic suffix strip below, but this is not a real
 * stemmer).
 */
public class TfIdfVectorizer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9']+");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "it", "to", "and", "of", "for", "in", "on", "my", "i", "me",
            "you", "your", "please", "hi", "hey", "just", "was", "this", "that", "with", "at", "be",
            "have", "has", "had", "not", "no", "so", "still", "even", "am", "are", "we", "us", "our"
    );

    private final Map<String, Double> idf = new HashMap<>();
    private final List<Map<String, Double>> docVectors = new ArrayList<>();

    public static List<String> tokenize(String text) {
        if (text == null) return List.of();
        String lower = text.toLowerCase();
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN_PATTERN.matcher(lower);
        while (matcher.find()) {
            String tok = matcher.group();
            if (tok.startsWith("@") || tok.startsWith("#")) continue;
            if (STOPWORDS.contains(tok)) continue;
            if (tok.length() <= 2) continue;
            // crude suffix stripping (not a real stemmer, just reduces sparsity)
            if (tok.endsWith("ing") && tok.length() > 5) tok = tok.substring(0, tok.length() - 3);
            else if (tok.endsWith("ed") && tok.length() > 4) tok = tok.substring(0, tok.length() - 2);
            else if (tok.endsWith("s") && tok.length() > 4 && !tok.endsWith("ss")) tok = tok.substring(0, tok.length() - 1);
            tokens.add(tok);
        }
        return tokens;
    }

    /** Fits IDF on the given corpus and stores TF-IDF vectors for each document (parallel index). */
    public void fit(List<String> corpus) {
        int n = corpus.size();
        Map<String, Integer> docFreq = new HashMap<>();
        List<Map<String, Integer>> termFreqs = new ArrayList<>();

        for (String doc : corpus) {
            List<String> tokens = tokenize(doc);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) tf.merge(t, 1, Integer::sum);
            termFreqs.add(tf);
            for (String term : tf.keySet()) docFreq.merge(term, 1, Integer::sum);
        }

        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            idf.put(e.getKey(), Math.log((double) (n + 1) / (e.getValue() + 1)) + 1.0);
        }

        for (Map<String, Integer> tf : termFreqs) {
            Map<String, Double> vec = new HashMap<>();
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                vec.put(e.getKey(), e.getValue() * idf.getOrDefault(e.getKey(), 0.0));
            }
            docVectors.add(normalize(vec));
        }
    }

    public Map<String, Double> vectorize(String text) {
        List<String> tokens = tokenize(text);
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        Map<String, Double> vec = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            vec.put(e.getKey(), e.getValue() * idf.getOrDefault(e.getKey(), 0.0));
        }
        return normalize(vec);
    }

    public List<Map<String, Double>> getDocVectors() {
        return docVectors;
    }

    public static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Map<String, Double> smaller = a.size() < b.size() ? a : b;
        Map<String, Double> larger = a.size() < b.size() ? b : a;
        double dot = 0.0;
        for (Map.Entry<String, Double> e : smaller.entrySet()) {
            Double other = larger.get(e.getKey());
            if (other != null) dot += e.getValue() * other;
        }
        return dot; // both vectors are already L2-normalized, so dot product == cosine similarity
    }

    private static Map<String, Double> normalize(Map<String, Double> vec) {
        double norm = Math.sqrt(vec.values().stream().mapToDouble(v -> v * v).sum());
        if (norm == 0.0) return vec;
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, Double> e : vec.entrySet()) out.put(e.getKey(), e.getValue() / norm);
        return out;
    }
}
