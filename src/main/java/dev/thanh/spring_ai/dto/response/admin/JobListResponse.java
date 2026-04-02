package dev.thanh.spring_ai.dto.response.admin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class JobListResponse {
    private JobStatsDto stats;
    private List<BackgroundJobDto> jobs;
}
