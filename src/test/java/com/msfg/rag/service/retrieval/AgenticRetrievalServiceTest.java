package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.service.ai.Intent;
import com.msfg.rag.service.ai.IntentRouterService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.msfg.rag.TestBrains.DEFAULT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticRetrievalServiceTest {

    private final IntentRouterService intentRouter = mock(IntentRouterService.class);
    private final RetrievalPlannerService planner = mock(RetrievalPlannerService.class);
    private final VocabularyService vocabulary = mock(VocabularyService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);

    private final AgenticRetrievalService service =
            new AgenticRetrievalService(intentRouter, planner, vocabulary, retrieval);

    @Test
    void retriesWithRewrittenQuestionWhenInitialRetrievalIsWeak() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE));
        RetrievalResult weak = RetrievalResult.empty();
        RetrievalResult strong = new RetrievalResult(List.of(chunk()), 0.82, true);
        PlannedEvidence sideEvidence = PlannedEvidence.empty();

        when(intentRouter.route("PMI", "/learn", "PUBLIC")).thenReturn(Intent.PAGE_GUIDANCE);
        when(intentRouter.isCalculationRequest("PMI")).thenReturn(false);
        when(planner.plan(Intent.PAGE_GUIDANCE, "/learn", "PUBLIC")).thenReturn(plan);
        when(vocabulary.previewExpansion(DEFAULT_ID, "PMI")).thenReturn("private mortgage insurance");
        when(retrieval.retrieve("PMI", DEFAULT_ID, SourceVisibility.PUBLIC)).thenReturn(weak);
        when(retrieval.retrieve("private mortgage insurance", DEFAULT_ID, SourceVisibility.PUBLIC)).thenReturn(strong);
        when(planner.collect(DEFAULT_ID, plan, "private mortgage insurance", "/learn", "PUBLIC"))
                .thenReturn(sideEvidence);

        AgenticRetrievalService.AgenticPlan agenticPlan =
                service.plan("PMI", DEFAULT_ID, "/learn", "PUBLIC");
        AgenticRetrievalService.AgenticRetrievalResult result =
                service.retrieve(agenticPlan, "PMI", DEFAULT_ID, "/learn", "PUBLIC", SourceVisibility.PUBLIC);

        assertSame(strong, result.retrieval());
        assertSame(sideEvidence, result.sideEvidence());
        assertEquals("private mortgage insurance", result.selectedQuery());
        assertEquals(2, result.attempts().size());
        assertEquals("initial", result.attempts().get(0).step());
        assertEquals("rewrite_retry", result.attempts().get(1).step());
        assertTrue(result.confidenceReason().containsKey("retrieval_attempts"));
    }

    @Test
    void doesNotRetryWhenInitialRetrievalIsSufficient() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS));
        RetrievalResult strong = new RetrievalResult(List.of(chunk()), 0.91, true);

        when(intentRouter.route("What is PMI?", null, null)).thenReturn(Intent.GUIDELINE_QUESTION);
        when(planner.plan(Intent.GUIDELINE_QUESTION, null, null)).thenReturn(plan);
        when(vocabulary.previewExpansion(DEFAULT_ID, "What is PMI?")).thenReturn("What is PMI? private mortgage insurance");
        when(retrieval.retrieve("What is PMI?", DEFAULT_ID, SourceVisibility.PUBLIC)).thenReturn(strong);
        when(planner.collect(DEFAULT_ID, plan, "What is PMI?", null, null)).thenReturn(PlannedEvidence.empty());

        AgenticRetrievalService.AgenticPlan agenticPlan =
                service.plan("What is PMI?", DEFAULT_ID, null, null);
        AgenticRetrievalService.AgenticRetrievalResult result =
                service.retrieve(agenticPlan, "What is PMI?", DEFAULT_ID, null, null, SourceVisibility.PUBLIC);

        assertSame(strong, result.retrieval());
        assertEquals(1, result.attempts().size());
        verify(retrieval, never()).retrieve(eq("What is PMI? private mortgage insurance"), any(), any());
    }

    @Test
    void doesNotCollectSideEvidenceWhenCorpusEvidenceIsInsufficient() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS, SourceKind.PAGE_GUIDE));
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.PAGE_GUIDANCE);
        when(planner.plan(Intent.PAGE_GUIDANCE, "/", "PUBLIC")).thenReturn(plan);
        when(vocabulary.previewExpansion(DEFAULT_ID, "unknown")).thenReturn("unknown");
        when(retrieval.retrieve("unknown", DEFAULT_ID, SourceVisibility.PUBLIC)).thenReturn(RetrievalResult.empty());

        AgenticRetrievalService.AgenticPlan agenticPlan =
                service.plan("unknown", DEFAULT_ID, "/", "PUBLIC");
        AgenticRetrievalService.AgenticRetrievalResult result =
                service.retrieve(agenticPlan, "unknown", DEFAULT_ID, "/", "PUBLIC", SourceVisibility.PUBLIC);

        assertEquals(0.0, result.retrieval().confidence());
        assertSame(PlannedEvidence.empty(), result.sideEvidence());
        verify(planner, never()).collect(any(), any(), anyString(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void confidenceReasonIncludesLegacyKeysAndStructuredAttempts() {
        RetrievalPlan plan = new RetrievalPlan(Set.of(SourceKind.CORPUS));
        RetrievalResult strong = new RetrievalResult(List.of(chunk()), 0.77, true);
        when(intentRouter.route(anyString(), any(), any())).thenReturn(Intent.GUIDELINE_QUESTION);
        when(planner.plan(Intent.GUIDELINE_QUESTION, null, null)).thenReturn(plan);
        when(vocabulary.previewExpansion(DEFAULT_ID, "What is PMI?")).thenReturn("What is PMI?");
        when(retrieval.retrieve("What is PMI?", DEFAULT_ID, SourceVisibility.PUBLIC)).thenReturn(strong);
        when(planner.collect(DEFAULT_ID, plan, "What is PMI?", null, null)).thenReturn(PlannedEvidence.empty());

        var agenticPlan = service.plan("What is PMI?", DEFAULT_ID, null, null);
        Map<String, Object> reason = service
                .retrieve(agenticPlan, "What is PMI?", DEFAULT_ID, null, null, SourceVisibility.PUBLIC)
                .confidenceReason();

        assertEquals(0.77, reason.get("retrieval_confidence"));
        assertEquals(1, reason.get("source_count"));
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) reason.get("retrieval_attempts");
        assertEquals(List.of(Map.of(
                "step", "initial",
                "query", "What is PMI?",
                "confidence", 0.77,
                "source_count", 1,
                "sufficient", true)), attempts);
    }

    private RetrievedChunk chunk() {
        return new RetrievedChunk(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Private mortgage insurance context.",
                "Fannie Mae",
                "AGENCY_GUIDELINE",
                "selling-guide.pdf",
                "Selling Guide",
                "B7",
                1,
                LocalDate.of(2026, 1, 1),
                0.8,
                0.7,
                0.82);
    }
}
