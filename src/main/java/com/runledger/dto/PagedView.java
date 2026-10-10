package com.runledger.dto;

import org.springframework.data.domain.Page;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * View-layer wrapper around a Spring {@link Page} that carries pre-built
 * previous/next URLs.
 *
 * <p>The templates can't easily reconstruct a pagination URL from
 * individual query parameters without either hardcoding the parameter
 * names into a shared fragment or doing URL encoding in Thymeleaf. Both
 * are worse than building the URLs once, in Java, where they're testable.
 *
 * <p>{@code extraParams} are appended to the pagination URLs. The controller
 * passes the current search parameters (metric, op, value, batch) so
 * clicking "Next" preserves the query. Null or blank values are omitted,
 * so an unsearched list produces clean URLs.
 *
 * @param items          the page's content
 * @param pageNumber     0-based page number
 * @param pageSize       page size
 * @param totalPages     total number of pages
 * @param totalElements  total number of matching rows
 * @param first          true if this is the first page
 * @param last           true if this is the last page
 * @param prevUrl        URL for the previous page; null if first
 * @param nextUrl        URL for the next page; null if last
 */
public record PagedView<T>(
        List<T> items,
        int pageNumber,
        int pageSize,
        int totalPages,
        long totalElements,
        boolean first,
        boolean last,
        String prevUrl,
        String nextUrl
) {

    public static <T> PagedView<T> of(Page<T> page,
                                      String basePath,
                                      Map<String, String> extraParams) {
        String prev = page.isFirst()
                ? null
                : buildUrl(basePath, page.getNumber() - 1, extraParams);
        String next = page.isLast()
                ? null
                : buildUrl(basePath, page.getNumber() + 1, extraParams);

        return new PagedView<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalPages(),
                page.getTotalElements(),
                page.isFirst(),
                page.isLast(),
                prev,
                next
        );
    }

    private static String buildUrl(String basePath,
                                   int pageNumber,
                                   Map<String, String> extraParams) {
        StringBuilder sb = new StringBuilder(basePath);
        sb.append("?page=").append(pageNumber);
        if (extraParams != null) {
            for (Map.Entry<String, String> e : extraParams.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isBlank()) continue;
                sb.append("&")
                        .append(encode(e.getKey()))
                        .append("=")
                        .append(encode(v));
            }
        }
        return sb.toString();
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}