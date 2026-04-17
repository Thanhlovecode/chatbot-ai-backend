package dev.thanh.spring_ai.service;

import dev.thanh.spring_ai.dto.response.admin.DocumentDto;
import dev.thanh.spring_ai.entity.Document;
import dev.thanh.spring_ai.enums.DocumentStatus;
import dev.thanh.spring_ai.exception.BadRequestException;
import dev.thanh.spring_ai.exception.ResourceNotFoundException;
import dev.thanh.spring_ai.repository.DocumentRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import dev.thanh.spring_ai.utils.SecurityUtils;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class AdminDocumentService {

    private final DocumentRepository documentRepository;
    private final QdrantClient qdrantClient;

    @Value("${spring.ai.vectorstore.qdrant.collection-name:spring}")
    private String collectionName;

    @Transactional(readOnly = true)
    public Page<DocumentDto> listDocuments(String status, Pageable pageable) {
        Page<Document> page;
        if ("ALL".equalsIgnoreCase(status)) {
            page = documentRepository.findAll(pageable);
        } else {
            DocumentStatus docStatus;
            try {
                docStatus = DocumentStatus.valueOf(status.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Invalid status value: '" + status
                        + "'. Allowed values: ALL, " + Arrays.toString(DocumentStatus.values()), e);
            }
            page = documentRepository.findByStatus(docStatus, pageable);
        }

        return page.map(doc -> DocumentDto.builder()
                .id(doc.getId())
                .fileName(doc.getFileName())
                .fileSize(doc.getFileSize())
                .chunkCount(doc.getChunkCount())
                .topic(doc.getTopic())
                .status(doc.getStatus())
                .uploadedAt(doc.getUploadedAt())
                .build());
    }

    @Transactional
    public void deleteDocument(UUID id) {
        Document document = documentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Document", "id", id));
        
        // 1. Delete from Qdrant by metadata file_id if it exists
        if (document.getFileId() != null && !document.getFileId().isBlank()) {
            try {
                Points.Filter filter = Points.Filter.newBuilder()
                        .addMust(Points.Condition.newBuilder()
                                .setField(Points.FieldCondition.newBuilder()
                                        .setKey("file_id")
                                        .setMatch(Points.Match.newBuilder()
                                                .setKeyword(document.getFileId())
                                                .build())
                                        .build())
                                .build())
                        .build();
                        
                qdrantClient.deleteAsync(
                        collectionName,
                        filter
                ).get();
                log.info("Deleted Qdrant vectors for document id: {}, file_id: {}", SecurityUtils.sanitizeLog(String.valueOf(id)), SecurityUtils.sanitizeLog(document.getFileId()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt flag
                log.error("Interrupted while deleting Qdrant vectors for document id: {}", SecurityUtils.sanitizeLog(String.valueOf(id)), e);
                throw new RuntimeException("Operation interrupted while deleting vectors in Qdrant", e);
            } catch (java.util.concurrent.ExecutionException e) {
                log.error("Failed to delete Qdrant vectors for document id: {}", SecurityUtils.sanitizeLog(String.valueOf(id)), e);
                throw new RuntimeException("Failed to delete corresponding vectors in Qdrant", e);
            }
        }
        
        // 2. Delete from PostgreSQL
        documentRepository.delete(document);
        log.info("Deleted document from DB: {}", SecurityUtils.sanitizeLog(String.valueOf(id)));
    }
}
