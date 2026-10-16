package com.ecommerce.web;

import com.ecommerce.dto.Dtos;
import com.ecommerce.service.SearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private static final Set<String> SORT_FIELDS = Set.of("order_date", "total_amount", "order_id");
    private static final Set<String> SORT_DIRS = Set.of("asc", "desc");

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @PostMapping("/orders")
    public Dtos.SearchResponse search(@RequestBody Dtos.SearchRequest payload) {
        Dtos.SearchRequest p = payload == null
                ? new Dtos.SearchRequest(null, null, null, null, null, null, null, null, 1, 20)
                : payload;
        if (!SORT_FIELDS.contains(p.sortBy()))
            throw ApiException.unprocessable("invalid sort_by: " + p.sortBy());
        if (!SORT_DIRS.contains(p.sortDir()))
            throw ApiException.unprocessable("invalid sort_dir: " + p.sortDir());
        try {
            return search.search(p);
        } catch (Exception e) {
            throw ApiException.unavailable("search unavailable: elasticsearch unreachable");
        }
    }
}
