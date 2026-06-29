package com.msfg.rag.controller;

import com.msfg.rag.TestBrains;
import com.msfg.rag.domain.Brain;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.BrainResolver;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerRetrievalTest {

    private final RetrievalService retrievalService = mock(RetrievalService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class),
            mock(MortgageDocumentRepository.class),
            retrievalService,
            mock(SyncService.class),
            brainResolver);

    DocumentAdminControllerRetrievalTest() {
        when(brainResolver.resolve(any())).thenReturn(new Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage"));
    }

    @Test
    void testRetrievalDefaultsToAdminWideVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null)).thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, null));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, null);
    }

    @Test
    void testRetrievalCanTargetInternalVisibility() {
        RetrievalResult result = RetrievalResult.empty();
        when(retrievalService.retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL))
                .thenReturn(result);

        assertEquals(result, controller.testRetrieval("What is PMI?", null, SourceVisibility.INTERNAL));
        verify(retrievalService).retrieveAdmin("What is PMI?", TestBrains.DEFAULT_ID, SourceVisibility.INTERNAL);
    }
}
