package dev.thanh.spring_ai.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CursorResponse<T> {
    private List<T> data;
    private String nextCursor;
    private boolean hasNext;
}
