package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.service.ai.Intent;
import com.msfg.rag.service.ai.IntentRouterService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Query-understanding and retrieval orchestration for the answer pipeline.
 *
 * <p>This is intentionally still deterministic: the first agentic loop is a
 * controlled retry with the editable vocabulary rewrite when the first corpus
 * retrieval is weak. It creates a clear place for later multi-step retrieval
 * tools without moving answer generation or compliance validation into the
 * retrieval layer.
 */
@Service
public class AgenticRetrievalService {

    private final IntentRouterService intentRouterService;
    private final RetrievalPlannerService retrievalPlannerService;
    private final VocabularyService vocabularyService;
    private final RetrievalService retrievalService;

    public AgenticRetrievalService(IntentRouterService intentRouterService,
                                   RetrievalPlannerService retrievalPlannerService,
                                   VocabularyService vocabularyService,
                                   RetrievalService retrievalService) {
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
        this.vocabularyService = vocabularyService;
        this.retrievalService = retrievalService;
    }

    public record AgenticPlan(
            Intent intent,
            RetrievalPlan retrievalPlan,
            String rewrittenQuestion,
            boolean calculationRequest) {}

    public record RetrievalAttempt(
            String step,
            String query,
            double confidence,
            int sourceCount,
            boolean sufficient) {

        Map<String, Object> toMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step);
            row.put("query", query);
            row.put("confidence", confidence);
            row.put("source_count", sourceCount);
            row.put("sufficient", sufficient);
            return Map.copyOf(row);
        }
    }

    public record AgenticRetrievalResult(
            Intent intent,
            RetrievalPlan retrievalPlan,
            String rewrittenQuestion,
            String selectedQuery,
            RetrievalResult retrieval,
            PlannedEvidence sideEvidence,
            List<RetrievalAttempt> attempts) {

        public Map<String, Object> confidenceReason() {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("retrieval_confidence", retrieval.confidence());
            reason.put("source_count", retrieval.chunks().size());
            reason.put("selected_query", selectedQuery);
            reason.put("retrieval_attempts", attempts.stream().map(RetrievalAttempt::toMap).toList());
            return Map.copyOf(reason);
        }
    }

    public AgenticPlan plan(String question, UUID brainId, String pageRoute, String surface) {
        Intent intent = intentRouterService.route(question, pageRoute, surface);
        RetrievalPlan plan = retrievalPlannerService.plan(intent, pageRoute, surface);
        String rewrittenQuestion = vocabularyService.previewExpansion(brainId, question);
        boolean calculationRequest = intentRouterService.isCalculationRequest(question);
        return new AgenticPlan(intent, plan, rewrittenQuestion, calculationRequest);
    }

    public AgenticRetrievalResult retrieve(AgenticPlan plan,
                                           String question,
                                           UUID brainId,
                                           String pageRoute,
                                           String surface,
                                           SourceVisibility visibility) {
        RetrievalResult initial = retrievalService.retrieve(question, brainId, visibility);
        RetrievalResult selected = initial;
        String selectedQuery = question;
        List<RetrievalAttempt> attempts = new java.util.ArrayList<>();
        attempts.add(attempt("initial", question, initial));

        if (!initial.sufficientEvidence() && materiallyDifferent(question, plan.rewrittenQuestion())) {
            RetrievalResult retry = retrievalService.retrieve(plan.rewrittenQuestion(), brainId, visibility);
            attempts.add(attempt("rewrite_retry", plan.rewrittenQuestion(), retry));
            if (preferRetry(initial, retry)) {
                selected = retry;
                selectedQuery = plan.rewrittenQuestion();
            }
        }

        PlannedEvidence sideEvidence = selected.sufficientEvidence()
                ? retrievalPlannerService.collect(brainId, plan.retrievalPlan(), selectedQuery, pageRoute, surface)
                : PlannedEvidence.empty();

        return new AgenticRetrievalResult(plan.intent(), plan.retrievalPlan(), plan.rewrittenQuestion(),
                selectedQuery, selected, sideEvidence, List.copyOf(attempts));
    }

    private static RetrievalAttempt attempt(String step, String query, RetrievalResult result) {
        return new RetrievalAttempt(step, query, result.confidence(), result.chunks().size(), result.sufficientEvidence());
    }

    private static boolean preferRetry(RetrievalResult initial, RetrievalResult retry) {
        if (retry.sufficientEvidence() && !initial.sufficientEvidence()) {
            return true;
        }
        return retry.confidence() > initial.confidence();
    }

    private static boolean materiallyDifferent(String original, String rewritten) {
        if (rewritten == null || rewritten.isBlank()) {
            return false;
        }
        return !normalize(original).equals(normalize(rewritten));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }
}
