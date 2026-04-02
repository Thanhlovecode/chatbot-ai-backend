package dev.thanh.spring_ai.controller;

import dev.thanh.spring_ai.dto.response.ResponseData;
import dev.thanh.spring_ai.dto.response.admin.JobListResponse;
import dev.thanh.spring_ai.service.JobRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;

@RestController
@RequestMapping("/api/v1/admin/jobs")
@PreAuthorize("hasRole('ADMIN')")
@Slf4j
@RequiredArgsConstructor
public class AdminJobController {

    private final JobRegistryService jobRegistryService;

    @GetMapping
    public ResponseEntity<ResponseData<JobListResponse>> listJobs() {
        JobListResponse response = jobRegistryService.listJobs();
        return ResponseEntity.ok(
                ResponseData.<JobListResponse>builder()
                        .status(200)
                        .message("Fetched jobs successfully")
                        .timestamp(ZonedDateTime.now())
                        .data(response)
                        .build()
        );
    }

    @PostMapping("/{jobId}/run")
    public ResponseEntity<ResponseData<Void>> triggerJob(@PathVariable String jobId) {
        try {
            jobRegistryService.triggerJob(jobId);
            return ResponseEntity.ok(
                    ResponseData.<Void>builder()
                            .status(200)
                            .message("Job triggered successfully")
                            .timestamp(ZonedDateTime.now())
                            .build()
            );
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    ResponseData.<Void>builder()
                            .status(400)
                            .message(e.getMessage())
                            .timestamp(ZonedDateTime.now())
                            .build()
            );
        }
    }
}
