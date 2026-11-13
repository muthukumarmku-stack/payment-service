package com.learning.paymentservice.common.web;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A stable JSON shape for paged results. Spring's own {@code PageImpl} is not
 * meant to be serialized directly: its JSON can change between versions.
 *
 * @param page zero-based page number
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <S, T> PageResponse<T> from(Page<S> source, Function<? super S, ? extends T> mapper) {
        return new PageResponse<>(
                source.getContent().stream().<T>map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
