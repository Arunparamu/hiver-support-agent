package com.hiver.supportagent.controller;

import com.hiver.supportagent.service.EvaluationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/eval")
@CrossOrigin(origins = "*")
public class EvalController {

    private final EvaluationService evaluationService;

    public EvalController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    /**
     * Runs the full evaluation harness against the golden set.
     * By default the LLM-judge is run against a small sample (25) to keep
     * this fast/cheap; pass judgeSampleSize=0 to skip it entirely, or a
     * larger number (up to golden set size) for a more thorough run.
     */
    @GetMapping("/run")
    public Map<String, Object> run(
            @RequestParam(defaultValue = "true") boolean includeJudge,
            @RequestParam(defaultValue = "25") int judgeSampleSize) {
        return evaluationService.runFullEvaluation(includeJudge, judgeSampleSize);
    }
}
