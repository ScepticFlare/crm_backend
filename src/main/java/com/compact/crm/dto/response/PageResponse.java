package com.compact.crm.dto.response;

import java.util.List;

// Minimal stable page shape (content + paging numbers), used by the WhatsApp
// consent admin endpoints - same fields as EmailSuppressionPage.
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
}
