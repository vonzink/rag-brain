package com.ragbrain.rag.service.ai;

/**
 * Pre-retrieval classification of an incoming question.
 * Only EDUCATIONAL questions proceed to RAG retrieval and the LLM.
 */
public enum QuestionCategory {

    /** General knowledge-base question — proceeds through the full RAG pipeline. */
    EDUCATIONAL,

    /** "Do I qualify?" / "Will I be approved?" — needs human review. */
    ELIGIBILITY,

    /** Legal questions (suing, contracts, disputes) — not legal advice. */
    LEGAL,

    /** Tax strategy questions — not tax advice. */
    TAX,

    /** Rate shopping — we have no live rate data connected. */
    LIVE_RATES,

    /** Requests to falsify, hide, or misrepresent — hard refusal. */
    FRAUD
}
