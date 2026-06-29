package com.msfg.rag.service.audit;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.RagTrace;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.repository.RagTraceRepository;
import com.msfg.rag.service.clarification.ClarificationDecision;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagTraceServiceTest {

    @Test
    void recordPublicDecisionIgnoresNullFactValues() {
        RagTraceRepository repository = mock(RagTraceRepository.class);
        when(repository.save(org.mockito.ArgumentMatchers.any(RagTrace.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        RagTraceService service = new RagTraceService(repository);

        ClarificationDecision decision = new ClarificationDecision(
                ResponseType.CLARIFY,
                "What is the occupancy?",
                List.of("occupancy"),
                Map.of("topic", "eligibility"));

        Map<String, Object> suppliedFacts = new LinkedHashMap<>();
        suppliedFacts.put("state", "CO");
        suppliedFacts.put("loan_type", null);

        service.recordPublicDecision(
                TestBrains.DEFAULT_ID,
                "s1",
                "Can I qualify?",
                suppliedFacts,
                decision,
                SourceVisibility.PUBLIC);

        ArgumentCaptor<RagTrace> traceCaptor = ArgumentCaptor.forClass(RagTrace.class);
        verify(repository).save(traceCaptor.capture());
        assertEquals(Map.of("state", "CO", "session_id", "s1"), traceCaptor.getValue().getCollectedFacts());
        assertEquals(Map.of("topic", "eligibility"), traceCaptor.getValue().getClarificationDecision());
        assertEquals("CLARIFY", traceCaptor.getValue().getResponseType());
    }
}
