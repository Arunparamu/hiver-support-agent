package com.hiver.supportagent.service;

import com.hiver.supportagent.model.Dtos.*;
import org.springframework.stereotype.Service;

@Service
public class AgentPipelineService {

    private final IntentClassifierService classifierService;
    private final ReplyDraftService replyDraftService;
    private final EscalationService escalationService;

    public AgentPipelineService(IntentClassifierService classifierService,
                                 ReplyDraftService replyDraftService,
                                 EscalationService escalationService) {
        this.classifierService = classifierService;
        this.replyDraftService = replyDraftService;
        this.escalationService = escalationService;
    }

    public ProcessResponse process(String message) {
        ClassificationResult classification = classifierService.classify(message);
        ReplyDraft reply = replyDraftService.draft(message, classification.intent());
        EscalationDecision escalation = escalationService.decide(message, classification);
        return new ProcessResponse(message, classification, reply, escalation);
    }
}
