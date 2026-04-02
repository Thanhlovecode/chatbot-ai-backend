package dev.thanh.spring_ai.dto.response.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class JobStatsDto {
    private long totalJobs;
    private long runningJobs;
    private long successfulJobsToday;
    private long failedJobsToday;
}
