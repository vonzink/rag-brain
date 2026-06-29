package com.msfg.rag.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MortgageDocumentTest {

    @Test
    void newDocumentsDefaultToInternalVisibilityAndApprovedTrust() {
        MortgageDocument document = new MortgageDocument();

        assertEquals(SourceVisibility.INTERNAL, document.getVisibility());
        assertEquals(SourceTrustLevel.APPROVED, document.getTrustLevel());
    }
}
