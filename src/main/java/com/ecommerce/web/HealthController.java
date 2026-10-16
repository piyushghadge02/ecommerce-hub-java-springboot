package com.ecommerce.web;

import com.ecommerce.config.AppProperties;
import com.ecommerce.dto.Dtos;
import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.service.SearchService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final SearchService search;
    private final AppProperties props;

    public HealthController(JdbcTemplate jdbc, ProductRepository products,
                            SearchService search, AppProperties props) {
        this.jdbc = jdbc;
        this.products = products;
        this.search = search;
        this.props = props;
    }

    @GetMapping("/health")
    public Dtos.HealthResponse health() {
        boolean pg = pgOk();
        boolean mongo = mongoOk();
        boolean es = search.ping();
        String status = (pg && mongo && es) ? "ok" : "degraded";
        return new Dtos.HealthResponse(status,
                pg ? "up" : "down", mongo ? "up" : "down", es ? "up" : "down",
                props.getSyncStrategy());
    }

    private boolean pgOk() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean mongoOk() {
        try {
            products.countActive(true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
