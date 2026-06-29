package com.msfg.rag.controller;

import com.msfg.rag.TestBrains;
import com.msfg.rag.config.RagProperties;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.BrainResolver;
import com.msfg.rag.service.publicapi.PublicAccessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskControllerTest {

    private final AskService askService = mock(AskService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final PublicAccessService publicAccessService = mock(PublicAccessService.class);
    private final RagProperties properties = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("admin-key"),
            new RagProperties.RateLimit(10));
    private final AskController controller = new AskController(
            askService, brainResolver, publicAccessService, properties);

    AskControllerTest() {
        when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage"));
    }

    @Test
    void adminApiKeyCanUseLegacyAskRouteWithInternalVisibility() {
        AskRequest request = new AskRequest(null, "session-1", "What is PMI?", null, null, "/", "INTERNAL");
        AskResponse response = new AskResponse(UUID.randomUUID(), "answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(eq(request), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL)))
                .thenReturn(response);

        assertEquals(response, controller.ask(request, null, "admin-key", null, null).getBody());
        verify(publicAccessService, never()).validate(any(), any(), any());
        verify(askService).ask(eq(request), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.INTERNAL));
    }

    @Test
    void publicLegacyAskRequiresPublicValidationAndForcesPublicVisibility() {
        AskRequest request = new AskRequest(null, "session-1", "What is PMI?", null, null, "/", "INTERNAL");
        AskResponse response = new AskResponse(UUID.randomUUID(), "answer", List.of(), 0.8,
                false, "disclaimer", null, List.of(), null, UUID.randomUUID());
        when(askService.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC)))
                .thenReturn(response);

        assertEquals(response, controller.ask(
                request, null, null, "public-token", "https://example.com").getBody());

        verify(publicAccessService).validate(TestBrains.DEFAULT_ID, "public-token", "https://example.com");
        ArgumentCaptor<AskRequest> requestCaptor = ArgumentCaptor.forClass(AskRequest.class);
        verify(askService).ask(requestCaptor.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
        assertEquals("PUBLIC", requestCaptor.getValue().surface());
    }
}
