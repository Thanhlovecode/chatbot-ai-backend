package dev.thanh.spring_ai.dto.response;

import lombok.*;

import java.time.ZonedDateTime;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ResponseData<T> {
    private int status;
    private String message;
    private ZonedDateTime timestamp;
    private T data;
}
