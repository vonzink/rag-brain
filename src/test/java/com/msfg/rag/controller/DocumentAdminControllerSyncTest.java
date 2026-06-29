package com.msfg.rag.controller;

import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceTrustLevel;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.domain.SourceVisibility;
import com.msfg.rag.dto.DocumentUpdateRequest;
import com.msfg.rag.service.BrainResolver;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncReport;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.msfg.rag.TestBrains;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerSyncTest {

    private final SyncService syncService = mock(SyncService.class);
    private final BrainResolver brainResolver = mock(BrainResolver.class);
    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final MortgageDocumentRepository documentRepository = mock(MortgageDocumentRepository.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            ingestionService,
            documentRepository,
            mock(RetrievalService.class),
            syncService,
            brainResolver);

    DocumentAdminControllerSyncTest() {
        com.msfg.rag.domain.Brain brain = new com.msfg.rag.domain.Brain(TestBrains.DEFAULT_ID, "mortgage", "Mortgage");
        when(brainResolver.resolve(any())).thenReturn(brain);
    }

    @Test
    void syncPassesDryRunFlagThrough() {
        SyncReport report = new SyncReport(true, Map.of("skip", 1), List.of());
        when(syncService.sync(eq(true), eq(TestBrains.DEFAULT_ID))).thenReturn(report);

        assertEquals(report, controller.sync(true, null));
        verify(syncService).sync(eq(true), eq(TestBrains.DEFAULT_ID));
    }

    @Test
    void syncDefaultsToExecute() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(eq(false), eq(TestBrains.DEFAULT_ID))).thenReturn(report);

        assertEquals(report, controller.sync(false, null));
    }

    @Test
    void uploadPassesVisibilityAndTrustToIngestionAndDto() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "policy.txt", "text/plain", "content".getBytes());
        MortgageDocument saved = new MortgageDocument();
        saved.setBrainId(TestBrains.DEFAULT_ID);
        saved.setTitle("Policy");
        saved.setSourceName("Internal");
        saved.setSourceType(SourceType.INTERNAL_POLICY);
        saved.setVisibility(SourceVisibility.INTERNAL);
        saved.setTrustLevel(SourceTrustLevel.REFERENCE);
        saved.setFileName("policy.txt");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        var response = controller.upload(file, "Policy", "Internal", SourceType.INTERNAL_POLICY,
                SourceVisibility.INTERNAL, SourceTrustLevel.REFERENCE,
                null, null, null, null);

        assertEquals(SourceVisibility.INTERNAL.name(), response.getBody().visibility());
        assertEquals(SourceTrustLevel.REFERENCE.name(), response.getBody().trustLevel());
        verify(ingestionService).ingest(eq("policy.txt"), eq("content".getBytes()),
                eq("Policy"), eq("Internal"), eq(SourceType.INTERNAL_POLICY),
                eq(SourceVisibility.INTERNAL), eq(SourceTrustLevel.REFERENCE),
                isNull(), isNull(), isNull(), eq(TestBrains.DEFAULT_ID));
    }

    @Test
    void updateSavesVisibilityAndTrustAndDtoReturnsThem() {
        UUID documentId = UUID.randomUUID();
        MortgageDocument document = new MortgageDocument();
        document.setBrainId(TestBrains.DEFAULT_ID);
        document.setTitle("Old");
        document.setSourceName("Old Source");
        document.setSourceType(SourceType.AGENCY_GUIDELINE);
        document.setVisibility(SourceVisibility.PUBLIC);
        document.setTrustLevel(SourceTrustLevel.APPROVED);
        document.setFileName("old.txt");
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(MortgageDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = controller.update(documentId, new DocumentUpdateRequest(
                "New", "Internal", "INTERNAL_POLICY", "INTERNAL", "REFERENCE",
                "v2", null, null));

        assertEquals(SourceVisibility.INTERNAL, document.getVisibility());
        assertEquals(SourceTrustLevel.REFERENCE, document.getTrustLevel());
        assertEquals("INTERNAL", response.getBody().visibility());
        assertEquals("REFERENCE", response.getBody().trustLevel());
    }
}
