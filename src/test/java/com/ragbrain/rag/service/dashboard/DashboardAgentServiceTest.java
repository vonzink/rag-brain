package com.ragbrain.rag.service.dashboard;

import com.ragbrain.rag.TestBrains;
import com.ragbrain.rag.domain.Brain;
import com.ragbrain.rag.domain.SourceVisibility;
import com.ragbrain.rag.dto.AskRequest;
import com.ragbrain.rag.dto.AskResponse;
import com.ragbrain.rag.dto.DashboardAgentDtos.DashboardAskRequest;
import com.ragbrain.rag.dto.DashboardAgentDtos.UserContext;
import com.ragbrain.rag.service.AskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardAgentServiceTest {

    private final AskService askService = mock(AskService.class);
    private final DashboardToolRegistry registry = new DashboardToolRegistry();
    private final DashboardAgentService service = new DashboardAgentService(askService, registry);

    @Test
    void askUsesInternalVisibilityAndThreadsUserContextIntoFacts() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        AskResponse answer = new AskResponse(UUID.randomUUID(), "Answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(org.mockito.Mockito.any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL)))
                .thenReturn(answer);

        var response = service.ask(brain, new DashboardAskRequest(null, "s1",
                "What needs attention?", "/loans", null,
                new UserContext("user-1", "tenant-1", List.of("loan-officer"),
                        List.of("dashboard.loans.read")),
                Map.of("current_module", "loans")));

        assertEquals(answer, response.answer());
        assertFalse(response.availableTools().isEmpty());
        ArgumentCaptor<AskRequest> request = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(request.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL));
        assertEquals("INTERNAL", request.getValue().surface());
        assertEquals("user-1", request.getValue().facts().get("user_id"));
        assertEquals("tenant-1", request.getValue().facts().get("tenant_id"));
        assertEquals("loan-officer", request.getValue().facts().get("roles"));
        assertEquals("loans", request.getValue().facts().get("current_module"));
    }

    @Test
    void askAllowsSecureRetrievalVisibilityButKeepsInternalSurface() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "dashboard-brain", "Dashboard Brain");
        AskResponse answer = new AskResponse(UUID.randomUUID(), "Answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(org.mockito.Mockito.any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.SECURE)))
                .thenReturn(answer);

        service.ask(brain, new DashboardAskRequest(null, "s1",
                "Show secure file status", "/documents", "SECURE",
                new UserContext("user-1", "tenant-1", List.of(), List.of()),
                Map.of()));

        ArgumentCaptor<AskRequest> request = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(request.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.SECURE));
        assertEquals("INTERNAL", request.getValue().surface());
    }
}
