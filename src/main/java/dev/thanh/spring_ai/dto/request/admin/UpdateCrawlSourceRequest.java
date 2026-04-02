package dev.thanh.spring_ai.dto.request.admin;

import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class UpdateCrawlSourceRequest {

    @NotBlank(message = "Name cannot be blank")
    private String name;

    @NotBlank(message = "Base URL cannot be blank")
    @Pattern(regexp = "^(http|https)://.*$", message = "Base URL must be valid HTTP/HTTPS URL")
    private String baseUrl;

    private String cronSchedule;

    private Integer maxDepth;

    private CrawlSourceStatus status;
}

