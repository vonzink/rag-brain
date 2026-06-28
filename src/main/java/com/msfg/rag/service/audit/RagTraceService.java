package com.msfg.rag.service.audit;

import com.msfg.rag.domain.RagTrace;
import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.repository.RagTraceRepository;
import com.msfg.rag.service.ai.Intent;
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
                           boolean escalation) {
        RagTrace trace = new RagTrace();
        trace.setConversationId(conversationId);
        trace.setBrainId(brainId);
        trace.setUserQuestion(userQuestion);
        trace.setRewrittenQuestion(rewrittenQuestion);
        trace.setIntent(intent == null ? null : intent.name());
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
        trace.setHumanEscalationRequired(escalation);
        return repository.save(trace);
    }
}
