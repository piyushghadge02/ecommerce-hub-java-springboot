package com.ecommerce;

import com.ecommerce.config.AppProperties;
import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class EcommerceApplication {

    private static final Logger log = LoggerFactory.getLogger(EcommerceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
    }

    /** Startup: ensure PG tables exist (ddl-auto=update), ES index + Mongo indexes. */
    @Bean
    public ApplicationRunner initRunner(ProductRepository products, SearchService search,
                                        AppProperties props) {
        return args -> {
            products.ensureIndexes();
            boolean esOk = search.ensureIndex();
            log.info("startup complete: elasticsearch index {} (strategy={})",
                    esOk ? "ready" : "unavailable", props.getSyncStrategy());
        };
    }
}
