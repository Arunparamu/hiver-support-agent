package com.hiver.supportagent.controller;

import com.hiver.supportagent.data.DataStore;
import com.hiver.supportagent.model.Dtos.ProcessRequest;
import com.hiver.supportagent.model.Dtos.ProcessResponse;
import com.hiver.supportagent.model.GoldenExample;
import com.hiver.supportagent.model.HistoricalConversation;
import com.hiver.supportagent.service.AgentPipelineService;
import com.hiver.supportagent.service.LlmClientService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentPipelineService pipelineService;
    private final DataStore dataStore;
    private final LlmClientService llmClient;

    public AgentController(AgentPipelineService pipelineService, DataStore dataStore, LlmClientService llmClient) {
        this.pipelineService = pipelineService;
        this.dataStore = dataStore;
        this.llmClient = llmClient;
    }

    @PostMapping("/process")
    public ProcessResponse process(@RequestBody ProcessRequest request) {
        return pipelineService.process(request.message());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "brand", dataStore.getBrandName(),
                "llmEnabled", llmClient.isAvailable(),
                "historicalConversationCount", dataStore.getHistoricalConversations().size(),
                "goldenExampleCount", dataStore.getGoldenExamples().size()
        );
    }

    @GetMapping("/historical-sample")
    public List<HistoricalConversation> historicalSample(@RequestParam(defaultValue = "10") int limit) {
        List<HistoricalConversation> all = dataStore.getHistoricalConversations();
        return all.subList(0, Math.min(limit, all.size()));
    }

    @GetMapping("/golden-sample")
    public List<GoldenExample> goldenSample(@RequestParam(defaultValue = "20") int limit) {
        List<GoldenExample> all = dataStore.getGoldenExamples();
        return all.subList(0, Math.min(limit, all.size()));
    }
}
