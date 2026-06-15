package com.msfg.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.domain.Conversation;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.provider.AiResponse;
import com.msfg.rag.repository.AnswerSourceRepository;
import com.msfg.rag.repository.ConversationRepository;
import com.msfg.rag.repository.MessageRepository;
import com.msfg.rag.service.ai.AnswerValidationService;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.PromptBuilderService;
import com.msfg.rag.service.ai.QuestionCategory;
import com.msfg.rag.service.ai.QuestionClassifierService;
import com.msfg.rag.service.audit.AuditLogService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Brain-awareness of the write path: the resolved brainId must reach the
 * REQUIRES_NEW audit record (explicit-param carrier), and conversation reuse
 * must require a brain match (no cross-brain conversation sharing).
 */
class AskServiceBrainTest {

    private static final UUID BRAIN_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRAIN_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final QuestionClassifierService classifier = mock(QuestionClassifierService.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final PromptBuilderService promptBuilder = mock(PromptBuilderService.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final AnswerSourceRepository sources = mock(AnswerSourceRepository.class);

    private AskService askService() {
        when(classifier.classify(anyString(), any())).thenReturn(QuestionCategory.EDUCATIONAL);
        // Insufficient evidence → short refusal path: still saves conversation,
        // message, sources, and records the audit row — all the writers we care about.
        when(retrieval.retrieve(anyString(), any())).thenReturn(RetrievalResult.empty());
        when(promptBuilder.disclaimer(any())).thenReturn("pack-disclaimer");
        when(messages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sources.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversations.save(any())).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            if (c.getId() == null) {
                ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
            }
            return c;
        });
        DomainPackRegistry registry = TestPacks.registryFor(java.util.Map.of(
                BRAIN_A, TestPacks.msfg(), BRAIN_B, TestPacks.msfg()));
        return new AskService(registry, classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(registry), audit,
                conversations, messages, sources, new ObjectMapper());
    }

    private AskRequest request(UUID conversationId) {
        return new AskRequest(conversationId, "session-1", "What is PMI?", null, null);
    }

    @Test
    void auditRecordCarriesResolvedBrainId() {
        askService().ask(request(null), BRAIN_A);

        ArgumentCaptor<UUID> brainArg = ArgumentCaptor.forClass(UUID.class);
        verify(audit).record(any(), brainArg.capture(), anyString(), anyList(),
                any(), anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
        assertEquals(BRAIN_A, brainArg.getValue(),
                "the brainId resolved at the controller must reach the REQUIRES_NEW audit record");
    }

    @Test
    void newConversationIsStampedWithResolvedBrain() {
        askService().ask(request(null), BRAIN_A);

        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertEquals(BRAIN_A, saved.getValue().getBrainId(),
                "a new conversation must be stamped with the resolved brain");
    }

    @Test
    void existingConversationFromAnotherBrainIsNotReused() {
        UUID conversationId = UUID.randomUUID();
        Conversation other = new Conversation();
        other.setUserSessionId("session-1");
        other.setBrainId(BRAIN_B);                       // belongs to a DIFFERENT brain
        ReflectionTestUtils.setField(other, "id", conversationId);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(other));

        askService().ask(request(conversationId), BRAIN_A);

        // The cross-brain conversation must NOT be extended; a new one is created.
        ArgumentCaptor<Conversation> saved = ArgumentCaptor.forClass(Conversation.class);
        verify(conversations).save(saved.capture());
        assertEquals(BRAIN_A, saved.getValue().getBrainId());
        assertNotEquals(conversationId, saved.getValue().getId(),
                "a conversation from another brain must not be reused");
    }

    @Test
    void existingConversationFromSameBrainIsReused() {
        UUID conversationId = UUID.randomUUID();
        Conversation same = new Conversation();
        same.setUserSessionId("session-1");
        same.setBrainId(BRAIN_A);                        // same brain, same session
        ReflectionTestUtils.setField(same, "id", conversationId);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(same));

        askService().ask(request(conversationId), BRAIN_A);

        // Same-brain, same-session conversation is reused: no new conversation saved.
        verify(conversations, never()).save(any());
    }
}
