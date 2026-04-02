package dev.thanh.spring_ai.enums;

public enum JobStatus {
    /** Job is currently running. */
    RUNNING,
    /** Last execution completed successfully. */
    SUCCESS,
    /** Last execution failed. */
    FAILED,
    /** No executions recorded yet, or last execution is not RUNNING. Used as computed state. */
    IDLE
}
