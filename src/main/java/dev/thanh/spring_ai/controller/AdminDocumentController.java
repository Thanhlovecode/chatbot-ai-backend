package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.dto.response.admin.DocumentDto;
import dev.thanh.spring_ai.service.AdminDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/documents")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mock.enabled", havingValue = "false", matchIfMissing = true)
public class AdminDocumentController {

    private final AdminDocumentService adminDocumentService;

    @GetMapping
    public ResponseEntity<ResponseData<Page<DocumentDto>>> listDocuments(
            @RequestParam(defaultValue = "ALL") String status,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<DocumentDto> page = adminDocumentService.listDocuments(status, pageable);

        return ResponseEntity.ok(
                ResponseData.<Page<DocumentDto>>builder()
                        .status(200)
                        .message("Fetched documents successfully")
                        .timestamp(ZonedDateTime.now())
                        .data(page)
                        .build()
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ResponseData<Void>> deleteDocument(@PathVariable UUID id) {
        adminDocumentService.deleteDocument(id);
        
        return ResponseEntity.ok(
                ResponseData.<Void>builder()
                        .status(200)
                        .message("Document and associated vectors deleted successfully")
                        .timestamp(ZonedDateTime.now())
                        .build()
        );
    }
}
