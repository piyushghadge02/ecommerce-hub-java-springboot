package com.ecommerce.web;

import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.service.SearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Base for API integration tests: full Spring context, live local DBs,
 *  ISOLATED resources (ecommerce_test PG/Mongo, orders_test ES index).
 *  PG changes roll back per test; Mongo/ES are cleaned explicitly. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
public abstract class AbstractIT {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    protected ProductRepository products;

    @Autowired
    protected SearchService search;

    @BeforeEach
    void cleanStores() {
        products.deleteAll();
        products.ensureIndexes();
        search.deleteIndex("orders_test");
        search.ensureIndex("orders_test");
    }

    @AfterEach
    void cleanEs() {
        search.deleteIndex("orders_test");
    }

    protected String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }
}
