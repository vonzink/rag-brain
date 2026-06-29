package com.msfg.rag.service.audit;

import com.msfg.rag.domain.RagTrace;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.repository.RagTraceRepository;
import com.msfg.rag.service.ai.Intent;
import com.msfg.rag.service.clarification.ClarificationDecision;
import com.msfg.rag.service.retrieval.PlannedEvidence;
import com.msfg.rag.service.retrieval.RetrievalPlan;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RagTraceService {

    private final RagTraceRepository repository;

    public RagTraceService(RagTraceRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagTrace record(UUID conversationId,
                           UUID brainId,
                           String userQuestion,
                           String rewrittenQuestion,
                           Intent intent,
                           RetrievalPlan plan,
                           List<RetrievedChunk> chunks,
                           PlannedEvidence evidence,
                           List<CitationDto> citations,
                           String finalAnswer,
                           Double confidence,
                           boolean escalation,
                           ResponseType responseType,
                           ClarificationDecision clarificationDecision,
                           SourceVisibility visibility,
                           Map<String, Object> confidenceReason,
                           String validationOutcome) {
        RagTrace trace = new RagTrace();
        trace.setConversationId(conversationId);
        trace.setBrainId(brainId);
        trace.setUserQuestion(userQuestion);
        trace.setRewrittenQuestion(rewrittenQuestion);
        trace.setIntent(intent == null ? null : intent.name());
        trace.setResponseType(responseType == null ? ResponseType.ANSWER.name() : responseType.name());
        trace.setRetrievalPlan(Map.of(
                "indexes", plan == null ? List.of() : plan.indexes().stream().map(Enum::name).toList()));
        trace.setRetrievedContext(chunks == null ? List.of() : chunks.stream()
                .map(c -> Map.<String, Object>of(
                        "chunk_id", String.valueOf(c.chunkId()),
                        "parent_chunk_id", c.parentChunkId() == null ? "" : String.valueOf(c.parentChunkId()),
                        "document_id", String.valueOf(c.documentId()),
                        "document_name", String.valueOf(c.documentName()),
                        "source_name", String.valueOf(c.sourceName()),
                        "section", c.section() == null ? "" : c.section(),
                        "hierarchy_path", c.hierarchyPath() == null ? "" : c.hierarchyPath(),
                        "combined_score", c.combinedScore()))
                .toList());
        trace.setSideEvidence(Map.of(
                "page_guides", evidence == null ? List.of() : evidence.pageGuides().stream()
                        .map(g -> Map.of("id", String.valueOf(g.getId()), "title", g.getTitle()))
                        .toList(),
                "source_links", evidence == null ? List.of() : evidence.links().stream()
                        .map(l -> Map.of("id", String.valueOf(l.getId()), "name", l.getName(),
                                "authority", l.getAuthority().name()))
                        .toList()));
        trace.setCitations(citations == null ? List.of() : citations.stream()
                .map(c -> Map.<String, Object>of(
                        "source_name", c.sourceName() == null ? "" : c.sourceName(),
                        "document_name", c.documentName() == null ? "" : c.documentName(),
                        "section", c.section() == null ? "" : c.section(),
                        "page_number", c.pageNumber() == null ? "" : c.pageNumber(),
                        "effective_date", c.effectiveDate() == null ? "" : c.effectiveDate()))
                .toList());
        trace.setFinalAnswer(finalAnswer);
        trace.setConfidenceScore(confidence);
        trace.setClarificationDecision(clarificationDecision == null
                ? Map.of("decision", "answer")
                : clarificationDecision.reason());
        trace.setMissingFacts(clarificationDecision == null
                ? List.of()
                : clarificationDecision.missingFacts());
        trace.setVisibilityFilter(visibility == null ? SourceVisibility.PUBLIC.name() : visibility.name());
        trace.setConfidenceReason(confidenceReason == null
                ? Map.of("confidence", confidence == null ? 0.0 : confidence)
                : confidenceReason);
        trace.setValidationOutcome(validationOutcome == null ? "valid" : validationOutcome);
        trace.setHumanEscalationRequired(escalation);
        return repository.save(trace);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RagTrace recordPublicDecision(UUID brainId,
                                         String sessionId,
                                         String userQuestion,
                                         ClarificationDecision decision,
                                         SourceVisibility visibility) {
        RagTrace trace = new RagTrace();
        trace.setBrainId(brainId);
        trace.setUserQuestion(userQuestion);
        trace.setResponseType(decision.responseType().name());
        trace.setClarificationDecision(decision.reason());
        trace.setMissingFacts(decision.missingFacts());
        trace.setCollectedFacts(Map.of("session_id", sessionId));
        trace.setRetrievedContext(List.of());
        trace.setVisibilityFilter(visibility.name());
        trace.setConfidenceReason(Map.of("reason", "pre-retrieval public decision"));
        trace.setValidationOutcome("not_applicable");
        trace.setHumanEscalationRequired(decision.responseType() == ResponseType.ESCALATE);
        return repository.save(trace);
    }
}
