package com.msfg.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.domain.AnswerSource;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.domain.Conversation;
import com.msfg.rag.domain.Message;
import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.dto.CitationDto;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.repository.AnswerSourceRepository;
import com.msfg.rag.repository.ConversationRepository;
import com.msfg.rag.repository.MessageRepository;
import com.msfg.rag.service.ai.AnswerValidationService;
import com.msfg.rag.service.ai.Intent;
import com.msfg.rag.service.ai.IntentRouterService;
import com.msfg.rag.service.ai.ModelAnswer;
import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.OutputContractService;
import com.msfg.rag.service.ai.PromptBuilderService;
import com.msfg.rag.service.ai.QuestionCategory;
import com.msfg.rag.service.ai.QuestionClassifierService;
import com.msfg.rag.service.audit.AuditLogService;
import com.msfg.rag.service.audit.RagTraceService;
import com.msfg.rag.service.retrieval.PlannedEvidence;
import com.msfg.rag.service.retrieval.RetrievalPlan;
import com.msfg.rag.service.retrieval.RetrievalPlannerService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import com.msfg.rag.service.retrieval.VocabularyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * End-to-end question pipeline:
 * retrieve -> build prompt -> generate -> validate -> cite -> persist -> audit.
 *
 * Two refusal paths, both compliance-required:
 * 1. Insufficient evidence  -> "could not find enough information" response.
 * 2. Failed validation      -> escalation response (never the raw model text).
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final DomainPackRegistry packRegistry;

    private final QuestionClassifierService questionClassifierService;
    private final RetrievalService retrievalService;
    private final PromptBuilderService promptBuilderService;
    private final ModelRouterService modelRouterService;
    private final AnswerValidationService answerValidationService;
    private final AuditLogService auditLogService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AnswerSourceRepository answerSourceRepository;
    private final ObjectMapper objectMapper;
    private final IntentRouterService intentRouterService;
    private final RetrievalPlannerService retrievalPlannerService;
    private final OutputContractService outputContractService;
    private final VocabularyService vocabularyService;
    private final RagTraceService ragTraceService;

    public AskService(DomainPackRegistry packRegistry,
                      QuestionClassifierService questionClassifierService,
                      RetrievalService retrievalService,
                      PromptBuilderService promptBuilderService,
                      ModelRouterService modelRouterService,
                      AnswerValidationService answerValidationService,
                      AuditLogService auditLogService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      AnswerSourceRepository answerSourceRepository,
                      ObjectMapper objectMapper,
                      IntentRouterService intentRouterService,
                      RetrievalPlannerService retrievalPlannerService,
                      OutputContractService outputContractService,
                      VocabularyService vocabularyService,
                      RagTraceService ragTraceService) {
        this.packRegistry = packRegistry;
        this.questionClassifierService = questionClassifierService;
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.modelRouterService = modelRouterService;
        this.answerValidationService = answerValidationService;
        this.auditLogService = auditLogService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.objectMapper = objectMapper;
        this.intentRouterService = intentRouterService;
        this.retrievalPlannerService = retrievalPlannerService;
        this.outputContractService = outputContractService;
        this.vocabularyService = vocabularyService;
        this.ragTraceService = ragTraceService;
    }

    @Transactional
    public AskResponse ask(AskRequest request, UUID brainId) {
        Conversation conversation = resolveConversation(request, brainId);
        saveMessage(conversation, Message.ROLE_USER, request.question(), null);

        DomainPack.CannedAnswers canned =
                packRegistry.bundle(brainId).pack().guardrails().cannedAnswers();

        // 0. Pre-retrieval guardrail: questions we must not answer are caught
        //    here before any embedding or LLM spend.
        QuestionCategory category = questionClassifierService.classify(request.question(), brainId);
        if (category != QuestionCategory.EDUCATIONAL) {
            return refuse(conversation, request, brainId, RetrievalResult.empty(),
                    categoryAnswer(category, canned), null, "classified as " + category,
                    null, null, null, PlannedEvidence.empty());
        }

        Intent intent = intentRouterService.route(
                request.question(), request.pageRoute(), request.surface());
        RetrievalPlan plan = retrievalPlannerService.plan(
                intent, request.pageRoute(), request.surface());
        String rewrittenQuestion = vocabularyService.previewExpansion(brainId, request.question());

        // 1. Retrieve approved source context.
        RetrievalResult retrieval = retrievalService.retrieve(request.question(), brainId);

        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), null,
                    "insufficient evidence", rewrittenQuestion, intent, plan, PlannedEvidence.empty());
        }

        PlannedEvidence sideEvidence = retrievalPlannerService.collect(
                brainId, plan, request.question(), request.pageRoute(), request.surface());

        // 3. Build the locked prompt and call the model (with fallback).
        String prompt = promptBuilderService.build(request.question(), retrieval.chunks(), brainId);
        ModelRouterService.RoutedResponse routed =
                modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt), brainId);

        // 4. Parse the model's JSON answer.
        ModelAnswer modelAnswer = parseModelAnswer(routed.response().content());
        if (modelAnswer == null) {
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "unparseable model response", rewrittenQuestion, intent, plan, sideEvidence);
        }

        // 4a. A model refusal must surface as one coherent refusal. When the
        //     model flags escalation AND cites nothing, it did not ground an
        //     answer — backfilling citations would decorate refusal text with
        //     sources it never used. Escalations WITH citations pass through:
        //     a cited answer plus an escalation flag is a meaningful response.
        if (Boolean.TRUE.equals(modelAnswer.humanEscalationRequired())
                && (modelAnswer.citations() == null || modelAnswer.citations().isEmpty())) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), prompt,
                    "model escalated without citations", rewrittenQuestion, intent, plan, sideEvidence);
        }

        // 4b. Salvage grounded answers that omit citations. The answer model
        //     sometimes returns a correct, grounded answer with no citations
        //     array; attach the approved source chunks rather than discard the
        //     answer and escalate. We only reach here when retrieval evidence
        //     was sufficient, so the chunks are the exact sources the prompt was
        //     grounded in (and they are already persisted as the answer trail).
        if (modelAnswer.citations() == null || modelAnswer.citations().isEmpty()) {
            log.info("Model answer omitted citations; attaching {} retrieved source(s)",
                    retrieval.chunks().size());
        }
        modelAnswer = ensureCitations(modelAnswer, retrieval.chunks());

        // 5. Compliance validation — failed answers are never shown.
        var validation = answerValidationService.validate(modelAnswer, true, brainId);
        if (!validation.valid()) {
            log.warn("Answer rejected by validator: {}", validation.failureReason());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    validation.failureReason(), rewrittenQuestion, intent, plan, sideEvidence);
        }

        boolean escalate = Boolean.TRUE.equals(modelAnswer.humanEscalationRequired());
        double confidence = modelAnswer.confidence() != null
                ? modelAnswer.confidence()
                : retrieval.confidence();
        List<CitationDto> citations = modelAnswer.citations() == null
                ? List.of()
                : modelAnswer.citations();

        // 6. Persist the assistant message and its citation trail.
        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT,
                modelAnswer.answer(), routed.response());
        saveAnswerSources(assistantMessage, retrieval.chunks());

        // 7. Audit log (own transaction, survives rollbacks). brainId is passed
        //    explicitly: record() runs in a REQUIRES_NEW transaction where
        //    ambient request context is unreliable.
        auditLogService.record(conversation.getId(), conversation.getBrainId(), request.question(),
                retrieval.chunks(), prompt, modelAnswer.answer(), routed.response().providerName(),
                routed.response().modelName(), confidence, routed.fallbackUsed(), escalate);

        OutputContractService.OutputContract contract = outputContractService.build(sideEvidence);
        UUID traceId = ragTraceService.record(conversation.getId(), conversation.getBrainId(),
                request.question(), rewrittenQuestion, intent, plan, retrieval.chunks(), sideEvidence,
                citations, modelAnswer.answer(), confidence, escalate).getId();

        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, promptBuilderService.disclaimer(brainId),
                contract.recommendedPage(), contract.links(), contract.nextAction(), traceId);
    }

    // ------------------------------------------------------------------

    private String categoryAnswer(QuestionCategory category, DomainPack.CannedAnswers canned) {
        return switch (category) {
            case ELIGIBILITY -> canned.escalation();
            case LEGAL -> canned.legal();
            case TAX -> canned.tax();
            case LIVE_RATES -> canned.liveRates();
            case FRAUD -> canned.fraud();
            case EDUCATIONAL -> throw new IllegalStateException(
                    "EDUCATIONAL questions must go through the RAG pipeline");
        };
    }

    private AskResponse refuse(Conversation conversation,
                               AskRequest request,
                               UUID brainId,
                               RetrievalResult retrieval,
                               String answerText,
                               String prompt,
                               String reason,
                               String rewrittenQuestion,
                               Intent intent,
                               RetrievalPlan plan,
                               PlannedEvidence sideEvidence) {
        log.info("Refusal/escalation for conversation {}: {}", conversation.getId(), reason);

        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT, answerText, null);
        saveAnswerSources(assistantMessage, retrieval.chunks());

        auditLogService.record(conversation.getId(), conversation.getBrainId(), request.question(),
                retrieval.chunks(), prompt, answerText, null, null, retrieval.confidence(), false, true);

        UUID traceId = ragTraceService.record(conversation.getId(), conversation.getBrainId(),
                request.question(), rewrittenQuestion, intent, plan, retrieval.chunks(), sideEvidence,
                List.of(), answerText, retrieval.confidence(), true).getId();

        return new AskResponse(conversation.getId(), answerText, List.of(),
                retrieval.confidence(), true, promptBuilderService.disclaimer(brainId),
                null, List.of(), null, traceId);
    }

    private Conversation resolveConversation(AskRequest request, UUID brainId) {
        if (request.conversationId() != null) {
            Conversation existing = conversationRepository
                    .findById(request.conversationId()).orElse(null);
            // The conversation must belong to the same website session AND the
            // same brain — prevents one visitor from reading or extending
            // another's chat, and prevents cross-brain conversation sharing.
            if (existing != null
                    && Objects.equals(existing.getUserSessionId(), request.sessionId())
                    && Objects.equals(existing.getBrainId(), brainId)) {
                return existing;
            }
        }
        Conversation conversation = new Conversation();
        conversation.setUserSessionId(request.sessionId());
        conversation.setSource("website");
        conversation.setBrainId(brainId);
        return conversationRepository.save(conversation);
    }

    private Message saveMessage(Conversation conversation, String role, String content,
                                com.msfg.rag.provider.AiResponse aiResponse) {
        Message message = new Message();
        message.setConversation(conversation);
        message.setBrainId(conversation.getBrainId());
        message.setRole(role);
        message.setContent(content);
        if (aiResponse != null) {
            message.setModelProvider(aiResponse.providerName());
            message.setModelName(aiResponse.modelName());
            message.setPromptTokens(aiResponse.promptTokens());
            message.setCompletionTokens(aiResponse.completionTokens());
        }
        return messageRepository.save(message);
    }

    private void saveAnswerSources(Message message, List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            AnswerSource source = new AnswerSource();
            source.setBrainId(message.getBrainId());
            source.setMessageId(message.getId());
            source.setDocumentId(chunk.documentId());
            source.setChunkId(chunk.chunkId());
            source.setSimilarityScore(chunk.combinedScore());
            source.setSourceName(chunk.sourceName());
            source.setDocumentName(chunk.documentName());
            source.setSection(chunk.section());
            source.setPageNumber(chunk.pageNumber());
            source.setEffectiveDate(chunk.effectiveDate());
            answerSourceRepository.save(source);
        }
    }

    /**
     * When the model returns a grounded answer but omits citations, attach the
     * retrieved approved sources so a correct answer is not discarded and
     * escalated. Model-supplied citations are kept as-is.
     */
    static ModelAnswer ensureCitations(ModelAnswer answer, List<RetrievedChunk> chunks) {
        if (answer.citations() != null && !answer.citations().isEmpty()) {
            return answer;
        }
        return new ModelAnswer(
                answer.answer(),
                citationsFromChunks(chunks),
                answer.confidence(),
                answer.humanEscalationRequired(),
                answer.disclaimer());
    }

    /** Maps retrieved source chunks to the citation shape returned to the website. */
    static List<CitationDto> citationsFromChunks(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new CitationDto(
                        chunk.sourceName(),
                        chunk.documentName(),
                        chunk.section(),
                        chunk.pageNumber() == null ? null : String.valueOf(chunk.pageNumber()),
                        chunk.effectiveDate() == null ? null : chunk.effectiveDate().toString()))
                .toList();
    }

    /**
     * Models sometimes wrap JSON in markdown fences or add prose around it;
     * extract the outermost JSON object before parsing.
     */
    private ModelAnswer parseModelAnswer(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String json = content.strip();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return objectMapper.readValue(json.substring(start, end + 1), ModelAnswer.class);
        } catch (Exception e) {
            log.error("Failed to parse model answer JSON: {}", e.getMessage());
            return null;
        }
    }
}
