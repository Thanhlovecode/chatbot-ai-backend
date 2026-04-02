package dev.thanh.spring_ai.dto.response.admin;

import dev.thanh.spring_ai.enums.JobStatus;
import dev.thanh.spring_ai.enums.JobType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class BackgroundJobDto {
    private String jobId;
    private String jobName;
    private JobType jobType;
    private String cronSchedule;
    private LocalDateTime lastRunAt;
    private Long lastDurationMs;
    private JobStatus status;
}
