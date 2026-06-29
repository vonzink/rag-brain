package com.msfg.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.msfg.rag.domain.AnswerSource;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.pack.DomainPackRegistry;
import com.msfg.rag.domain.Conversation;
import com.msfg.rag.domain.Message;
import com.msfg.rag.domain.ResponseType;
import com.msfg.rag.domain.SourceVisibility;
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
import com.msfg.rag.service.clarification.ClarificationDecision;
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

import java.util.List;
import java.util.Map;
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

    public AskResponse ask(AskRequest request, UUID brainId) {
        return ask(request, brainId, SourceVisibility.PUBLIC);
    }

    // NOTE: deliberately NOT @Transactional at the method level. This pipeline
    // makes blocking external calls (embedding + answer model); a method-wide
    // transaction would pin a JDBC connection across that I/O. Each persistence
    // step runs in its own short transaction (repository defaults, plus the
    // REQUIRES_NEW audit/trace writes). Provider failures are converted to a
    // compliance escalation below rather than bubbling up as a 500.
    public AskResponse ask(AskRequest request, UUID brainId, SourceVisibility visibility) {
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
                    null, null, null, PlannedEvidence.empty(), visibility);
        }

        Intent intent = intentRouterService.route(
                request.question(), request.pageRoute(), request.surface());
        RetrievalPlan plan = retrievalPlannerService.plan(
                intent, request.pageRoute(), request.surface());
        String rewrittenQuestion = vocabularyService.previewExpansion(brainId, request.question());

        // 1. Retrieve approved source context. A transient embedding/provider
        //    error becomes a graceful escalation, not a 500. A genuine
        //    misconfiguration ("not configured") still surfaces as 503.
        RetrievalResult retrieval;
        try {
            retrieval = retrievalService.retrieve(request.question(), brainId, visibility);
        } catch (RuntimeException e) {
            if (isNotConfigured(e)) {
                throw e;
            }
            log.error("Retrieval/embedding provider error for conversation {}: {}",
                    conversation.getId(), e.getMessage());
            return refuse(conversation, request, brainId, RetrievalResult.empty(), canned.escalation(),
                    null, "retrieval provider error", rewrittenQuestion, intent, plan,
                    PlannedEvidence.empty(), visibility);
        }

        // 2. Refuse early when there is no reliable source material.
        if (!retrieval.sufficientEvidence()) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), null,
                    "insufficient evidence", rewrittenQuestion, intent, plan, PlannedEvidence.empty(), visibility);
        }

        PlannedEvidence sideEvidence = retrievalPlannerService.collect(
                brainId, plan, request.question(), request.pageRoute(), request.surface());

        // 3. Build the locked prompt and call the model (with fallback). When the
        //    answer provider (and its fallback) fail, return a compliance
        //    escalation instead of surfacing a 500 to the visitor. A genuine
        //    misconfiguration ("not configured") still surfaces as 503.
        String prompt = promptBuilderService.build(request.question(), retrieval.chunks(), brainId, sideEvidence);
        ModelRouterService.RoutedResponse routed;
        try {
            routed = modelRouterService.generate(AiRequest.forGuidelineAnswer(prompt), brainId);
        } catch (RuntimeException e) {
            if (isNotConfigured(e)) {
                throw e;
            }
            log.error("Answer provider error for conversation {}: {}",
                    conversation.getId(), e.getMessage());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "answer provider error", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4. Parse the model's JSON answer.
        ModelAnswer modelAnswer = parseModelAnswer(routed.response().content());
        if (modelAnswer == null) {
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    "unparseable model response", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4a. A model refusal must surface as one coherent refusal. When the
        //     model flags escalation AND cites nothing, it did not ground an
        //     answer — backfilling citations would decorate refusal text with
        //     sources it never used. Escalations WITH citations pass through:
        //     a cited answer plus an escalation flag is a meaningful response.
        if (Boolean.TRUE.equals(modelAnswer.humanEscalationRequired())
                && (modelAnswer.citations() == null || modelAnswer.citations().isEmpty())) {
            return refuse(conversation, request, brainId, retrieval, canned.noSource(), prompt,
                    "model escalated without citations", rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4b. Compliance content gate on the model's RAW answer, BEFORE any
        //     citation backfill. Running this first means post-processing can
        //     never mask a prohibited-phrase / non-compliant answer behind
        //     attached sources.
        var contentValidation = answerValidationService.validateContent(modelAnswer, brainId);
        if (!contentValidation.valid()) {
            log.warn("Answer rejected by content validator: {}", contentValidation.failureReason());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    contentValidation.failureReason(), rewrittenQuestion, intent, plan, sideEvidence, visibility);
        }

        // 4c. Salvage grounded answers that omit citations. The answer model
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

        // 5. Final compliance backstop (incl. citation presence) — failed answers
        //     are never shown.
        var validation = answerValidationService.validate(modelAnswer, true, brainId);
        if (!validation.valid()) {
            log.warn("Answer rejected by validator: {}", validation.failureReason());
            return refuse(conversation, request, brainId, retrieval, canned.escalation(), prompt,
                    validation.failureReason(), rewrittenQuestion, intent, plan, sideEvidence, visibility);
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
                citations, modelAnswer.answer(), confidence, escalate,
                ResponseType.ANSWER, ClarificationDecision.answer(), visibility, Map.of(),
                Map.of("retrieval_confidence", retrieval.confidence(), "source_count", retrieval.chunks().size()),
                "valid").getId();

        return new AskResponse(conversation.getId(), modelAnswer.answer(), citations,
                confidence, escalate, promptBuilderService.disclaimer(brainId),
                contract.recommendedPage(), contract.links(), contract.nextAction(), traceId);
    }

    // ------------------------------------------------------------------

    /**
     * An operator misconfiguration (no provider/embedding key) is signalled with
     * an IllegalStateException whose message contains "not configured"; those must
     * surface as 503 (handled globally), not be masked as a routine escalation.
     */
    private static boolean isNotConfigured(Throwable e) {
        return e instanceof IllegalStateException
                && e.getMessage() != null
                && e.getMessage().contains("not configured");
    }

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
                               PlannedEvidence sideEvidence,
                               SourceVisibility visibility) {
        log.info("Refusal/escalation for conversation {}: {}", conversation.getId(), reason);

        Message assistantMessage = saveMessage(conversation, Message.ROLE_ASSISTANT, answerText, null);
        saveAnswerSources(assistantMessage, retrieval.chunks());

        auditLogService.record(conversation.getId(), conversation.getBrainId(), request.question(),
                retrieval.chunks(), prompt, answerText, null, null, retrieval.confidence(), false, true);

        UUID traceId = ragTraceService.record(conversation.getId(), conversation.getBrainId(),
                request.question(), rewrittenQuestion, intent, plan, retrieval.chunks(), sideEvidence,
                List.of(), answerText, retrieval.confidence(), true,
                ResponseType.ESCALATE, new ClarificationDecision(
                        ResponseType.ESCALATE,
                        null,
                        List.of(),
                        Map.of("decision", "escalate", "reason", reason)),
                visibility, Map.of(),
                Map.of("reason", reason), reason).getId();

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
