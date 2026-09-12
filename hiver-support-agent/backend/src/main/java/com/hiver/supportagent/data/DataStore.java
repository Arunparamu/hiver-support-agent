package com.hiver.supportagent.data;

import com.hiver.supportagent.model.GoldenExample;
import com.hiver.supportagent.model.HistoricalConversation;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads the historical (grounding) conversations and the golden evaluation
 * set into memory at startup. Both are plain CSVs so swapping in the real
 * Kaggle-derived data is a file-path change, not a code change.
 */
@Component
public class DataStore {

    private final ResourceLoader resourceLoader;

    @Value("${data.conversations-path}")
    private String conversationsPath;

    @Value("${data.golden-path}")
    private String goldenPath;

    @Value("${data.brand-name}")
    private String brandName;

    private List<HistoricalConversation> historicalConversations = new ArrayList<>();
    private List<GoldenExample> goldenExamples = new ArrayList<>();

    public DataStore(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void load() throws Exception {
        historicalConversations = loadHistorical(conversationsPath);
        goldenExamples = loadGolden(goldenPath);
    }

    private List<HistoricalConversation> loadHistorical(String path) throws Exception {
        List<HistoricalConversation> out = new ArrayList<>();
        Resource resource = resourceLoader.getResource(path);
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (CSVRecord r : parser) {
                out.add(new HistoricalConversation(
                        r.get("tweet_id"),
                        r.get("text"),
                        r.get("brand_reply"),
                        r.get("intent"),
                        r.get("order_id")
                ));
            }
        }
        return out;
    }

    private List<GoldenExample> loadGolden(String path) throws Exception {
        List<GoldenExample> out = new ArrayList<>();
        Resource resource = resourceLoader.getResource(path);
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (CSVRecord r : parser) {
                out.add(new GoldenExample(
                        Integer.parseInt(r.get("id")),
                        r.get("customer_text"),
                        r.get("gold_intent"),
                        Boolean.parseBoolean(r.get("gold_escalate")),
                        r.get("sample_group"),
                        r.get("notes")
                ));
            }
        }
        return out;
    }

    public List<HistoricalConversation> getHistoricalConversations() {
        return Collections.unmodifiableList(historicalConversations);
    }

    public List<GoldenExample> getGoldenExamples() {
        return Collections.unmodifiableList(goldenExamples);
    }

    public String getBrandName() {
        return brandName;
    }
}
