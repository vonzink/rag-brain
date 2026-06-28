package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rag_traces")
public class RagTrace {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "brain_id", nullable = false)
    private UUID brainId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "user_question", nullable = false, columnDefinition = "text")
    private String userQuestion;

    @Column(name = "rewritten_question", columnDefinition = "text")
    private String rewrittenQuestion;

    @Column(length = 80)
    private String intent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_plan", columnDefinition = "jsonb")
    private Map<String, Object> retrievalPlan;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_context", columnDefinition = "jsonb")
    private List<Map<String, Object>> retrievedContext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "side_evidence", columnDefinition = "jsonb")
    private Map<String, Object> sideEvidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> citations;

    @Column(name = "final_answer", columnDefinition = "text")
    private String finalAnswer;

    @Column(name = "confidence_score")
    private Double confidenceScore;

    @Column(name = "human_escalation_required", nullable = false)
    private boolean humanEscalationRequired;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getBrainId() { return brainId; }
    public void setBrainId(UUID brainId) { this.brainId = brainId; }
    public UUID getConversationId() { return conversationId; }
    public void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    public String getUserQuestion() { return userQuestion; }
    public void setUserQuestion(String userQuestion) { this.userQuestion = userQuestion; }
    public String getRewrittenQuestion() { return rewrittenQuestion; }
    public void setRewrittenQuestion(String rewrittenQuestion) { this.rewrittenQuestion = rewrittenQuestion; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Map<String, Object> getRetrievalPlan() { return retrievalPlan; }
    public void setRetrievalPlan(Map<String, Object> retrievalPlan) { this.retrievalPlan = retrievalPlan; }
    public List<Map<String, Object>> getRetrievedContext() { return retrievedContext; }
    public void setRetrievedContext(List<Map<String, Object>> retrievedContext) { this.retrievedContext = retrievedContext; }
    public Map<String, Object> getSideEvidence() { return sideEvidence; }
    public void setSideEvidence(Map<String, Object> sideEvidence) { this.sideEvidence = sideEvidence; }
    public List<Map<String, Object>> getCitations() { return citations; }
    public void setCitations(List<Map<String, Object>> citations) { this.citations = citations; }
    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }
    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }
    public boolean isHumanEscalationRequired() { return humanEscalationRequired; }
    public void setHumanEscalationRequired(boolean humanEscalationRequired) { this.humanEscalationRequired = humanEscalationRequired; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
