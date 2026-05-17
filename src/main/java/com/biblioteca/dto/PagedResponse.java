package com.biblioteca.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Minimal JSON shape for paginated responses. Spring's {@code Page<T>}
 * serializes a much larger object (with pageable, sort, numberOfElements...
 * fields) that bloats the wire payload and exposes Spring internals to the
 * client. Wrap with {@link #from(Page)} or {@link #from(Page, Function)}.
 */
public class PagedResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean first;
    private boolean last;

    public PagedResponse() {}

    public static <T> PagedResponse<T> from(Page<T> page) {
        PagedResponse<T> r = new PagedResponse<>();
        r.content = page.getContent();
        r.page = page.getNumber();
        r.size = page.getSize();
        r.totalElements = page.getTotalElements();
        r.totalPages = page.getTotalPages();
        r.first = page.isFirst();
        r.last = page.isLast();
        return r;
    }

    /** Maps each element through {@code mapper} (e.g. entity → DTO). */
    public static <S, T> PagedResponse<T> from(Page<S> page, Function<S, T> mapper) {
        PagedResponse<T> r = new PagedResponse<>();
        r.content = page.map(mapper).getContent();
        r.page = page.getNumber();
        r.size = page.getSize();
        r.totalElements = page.getTotalElements();
        r.totalPages = page.getTotalPages();
        r.first = page.isFirst();
        r.last = page.isLast();
        return r;
    }

    public List<T> getContent() { return content; }
    public void setContent(List<T> content) { this.content = content; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public boolean isFirst() { return first; }
    public void setFirst(boolean first) { this.first = first; }
    public boolean isLast() { return last; }
    public void setLast(boolean last) { this.last = last; }
}
