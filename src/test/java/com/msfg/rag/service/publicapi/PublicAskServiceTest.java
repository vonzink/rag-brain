package com.msfg.rag.service.publicapi;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.BrainProfile;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.PublicAskRequest;
import com.msfg.rag.repository.BrainRepository;
import com.msfg.rag.service.AskService;
import com.msfg.rag.service.clarification.ClarificationDecision;
import com.msfg.rag.service.clarification.ClarificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicAskServiceTest {

    private final BrainRepository brains = mock(BrainRepository.class);
    private final PublicAccessService access = mock(PublicAccessService.class);
    private final ClarificationService clarification = mock(ClarificationService.class);
    private final AskService ask = mock(AskService.class);
    private final PublicAskService service = new PublicAskService(brains, access, clarification, ask);

    @Test
    void clarifyResponseDoesNotCallAnswerModel() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("Can I use FHA?"), eq("PUBLIC"), any()))
                .thenReturn(new ClarificationDecision(ResponseType.CLARIFY,
                        "Is this for a primary residence?", List.of("occupancy"), Map.of("rule", "eligibility")));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "Can I use FHA?", "/", "PUBLIC", Map.of()));

        assertEquals("CLARIFY", response.responseType());
        assertEquals("Is this for a primary residence?", response.clarifyingQuestion());
        verify(ask, never()).ask(any(), any());
        verifyNoMoreInteractions(ask);
    }

    @Test
    void answerResponseMapsExistingAskResponse() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        var response = service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "PUBLIC", Map.of()));

        assertEquals("ANSWER", response.responseType());
        assertEquals("PMI is mortgage insurance.", response.answer());
        assertEquals(0.94, response.confidence());
    }

    @Test
    void publicRequestForcesPublicSurfaceAndVisibilityEvenWhenCallerSuppliesInternal() {
        Brain brain = new Brain(TestBrains.DEFAULT_ID, "generic", "Generic");
        UUID conversationId = UUID.randomUUID();
        when(brains.findBySlug("generic")).thenReturn(Optional.of(brain));
        when(access.validate(eq(TestBrains.DEFAULT_ID), eq("token"), eq("https://example.com")))
                .thenReturn(new BrainProfile());
        when(clarification.decide(eq(TestBrains.DEFAULT_ID), eq("What is PMI?"), eq("PUBLIC"), any()))
                .thenReturn(ClarificationDecision.answer());
        when(ask.ask(any(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC))).thenReturn(new AskResponse(
                conversationId, "PMI is mortgage insurance.", List.of(), 0.94, false,
                "disclaimer", null, List.of(), "next", UUID.randomUUID()));

        service.ask("generic", "token", "https://example.com",
                new PublicAskRequest("s1", "What is PMI?", "/", "INTERNAL", Map.of()));

        ArgumentCaptor<AskRequest> requestCaptor = ArgumentCaptor.forClass(AskRequest.class);
        verify(ask, times(1)).ask(requestCaptor.capture(), eq(TestBrains.DEFAULT_ID), eq(SourceVisibility.PUBLIC));
        assertEquals("PUBLIC", requestCaptor.getValue().surface());
    }
}
