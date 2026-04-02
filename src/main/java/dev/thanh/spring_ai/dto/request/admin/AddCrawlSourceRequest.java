package dev.thanh.spring_ai.dto.request.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AddCrawlSourceRequest {
    
    @NotBlank(message = "Name cannot be blank")
    private String name;
    
    @NotBlank(message = "Base URL cannot be blank")
    @Pattern(regexp = "^(http|https)://.*$", message = "Base URL must be valid HTTP/HTTPS URL")
    private String baseUrl;
    
    private String cronSchedule = "0 0 6 * * *";
    
    private Integer maxDepth = 3;
}
