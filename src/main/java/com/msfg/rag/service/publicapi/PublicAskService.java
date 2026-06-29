package com.msfg.rag.service.publicapi;

import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.PublicAskRequest;
import com.msfg.rag.dto.PublicAskResponse;
import com.msfg.rag.dto.PublicRecommendedPageDto;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.audit.RagTraceService;
import com.msfg.rag.service.clarification.ClarificationDecision;
import com.msfg.rag.service.clarification.ClarificationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PublicAskService {

    private final BrainRepository brains;
    private final PublicAccessService access;
    private final ClarificationService clarification;
    private final AskService askService;
    private final RagTraceService traceService;

    public PublicAskService(BrainRepository brains, PublicAccessService access,
                            ClarificationService clarification, AskService askService,
                            RagTraceService traceService) {
        this.brains = brains;
        this.access = access;
        this.clarification = clarification;
        this.askService = askService;
        this.traceService = traceService;
    }

    public PublicAskResponse ask(String slug, String token, String origin, PublicAskRequest req) {
        Brain brain = brains.findBySlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("Unknown brain: " + slug));
        access.validate(brain.getId(), token, origin);
        String surface = SourceVisibility.PUBLIC.name();
        Map<String, Object> facts = req.facts() == null ? Map.of() : req.facts();
        ClarificationDecision decision = clarification.decide(
                brain.getId(), req.message(), surface, facts);
        if (decision.responseType() == ResponseType.CLARIFY) {
            traceService.recordPublicDecision(brain.getId(), req.sessionId(), req.message(), facts,
                    decision, SourceVisibility.PUBLIC);
            return new PublicAskResponse("CLARIFY", decision.question(), null, decision.question(),
                    decision.missingFacts(), List.of(), List.of(), 0.0, null, null);
        }
        if (decision.responseType() == ResponseType.NAVIGATE) {
            traceService.recordPublicDecision(brain.getId(), req.sessionId(), req.message(), facts,
                    decision, SourceVisibility.PUBLIC);
            AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC);
            return mapAnswer(answer.humanEscalationRequired() ? "ESCALATE" : "NAVIGATE", answer);
        }
        AskResponse answer = askService.ask(toAskRequest(req, surface), brain.getId(), SourceVisibility.PUBLIC);
        return mapAnswer(answer.humanEscalationRequired() ? "ESCALATE" : "ANSWER", answer);
    }

    private static AskRequest toAskRequest(PublicAskRequest req, String surface) {
        return new AskRequest(null, req.sessionId(), req.message(), null, null, req.pageRoute(), surface);
    }

    private static PublicAskResponse mapAnswer(String responseType, AskResponse answer) {
        List<PublicRecommendedPageDto> pages = answer.recommendedPage() == null
                ? List.of()
                : List.of(new PublicRecommendedPageDto(
                        answer.recommendedPage().label(), answer.recommendedPage().route(), "Matched the current question."));
        return new PublicAskResponse(responseType, answer.answer(), answer.answer(), null, List.of(),
                answer.citations(), pages, answer.confidence(), answer.nextAction(), answer.conversationId());
    }
}
