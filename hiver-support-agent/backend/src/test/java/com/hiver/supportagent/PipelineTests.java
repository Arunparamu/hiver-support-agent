package com.hiver.supportagent;

import com.hiver.supportagent.model.Dtos.ClassificationResult;
import com.hiver.supportagent.model.Dtos.EscalationDecision;
import com.hiver.supportagent.service.EscalationService;
import com.hiver.supportagent.service.IntentClassifierService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PipelineTests {

    @Autowired
    private IntentClassifierService classifierService;

    @Autowired
    private EscalationService escalationService;

    @Test
    void keywordBaseline_classifiesRefundRequest() {
        ClassificationResult result = classifierService.keywordBaselineClassify(
                "hey I returned my order 2 weeks ago and still no refund");
        assertEquals("refund_request", result.intent());
    }

    @Test
    void keywordBaseline_classifiesOrderStatus() {
        ClassificationResult result = classifierService.keywordBaselineClassify(
                "where is my order? tracking hasn't updated in days");
        assertEquals("order_status", result.intent());
    }

    @Test
    void escalation_alwaysTriggersOnLegalThreat() {
        ClassificationResult classification = classifierService.keywordBaselineClassify(
                "my lawyer will be in touch, this is fraud");
        EscalationDecision decision = escalationService.decide(
                "my lawyer will be in touch, this is fraud", classification);
        assertTrue(decision.escalate());
        assertTrue(decision.triggeredRules().stream().anyMatch(r -> r.contains("safety_or_legal")));
    }

    @Test
    void escalation_autoHandlesPraise() {
        ClassificationResult classification = new ClassificationResult("praise_feedback", 0.9, "test", java.util.List.of());
        EscalationDecision decision = escalationService.decide("thanks for the great service!", classification);
        assertFalse(decision.escalate());
    }

    @Test
    void escalation_lowConfidenceTriggersEscalation() {
        ClassificationResult classification = new ClassificationResult("order_status", 0.2, "test", java.util.List.of());
        EscalationDecision decision = escalationService.decide("hmm not sure what's going on with this", classification);
        assertTrue(decision.escalate());
    }
}
