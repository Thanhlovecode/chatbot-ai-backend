package dev.thanh.spring_ai.controller;


import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.service.RagService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZonedDateTime;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rag")
@Slf4j(topic = "RAG-CONTROLLER")
public class RagController {

    private final RagService ragService;

    @PostMapping("/file")
    public ResponseData<Void> storeDataFile(@RequestParam("file") @NonNull MultipartFile file) {
        ragService.storeDataFile(file);
        return ResponseData.<Void>builder()
                .status(HttpStatus.CREATED.value())
                .message("Successfully stored data file")
                .timestamp(ZonedDateTime.now())
                .build();
    }
}
