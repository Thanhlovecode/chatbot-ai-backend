package dev.thanh.spring_ai.dto.request.admin;

import dev.thanh.spring_ai.enums.CrawlSourceStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Min(value = 1, message = "Max depth must be at least 1")
    @Max(value = 10, message = "Max depth must not exceed 10")
    private Integer maxDepth;

    private CrawlSourceStatus status;
}

