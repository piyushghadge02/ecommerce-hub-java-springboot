package com.ecommerce.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/** Elasticsearch client bean. Returns null (graceful degradation) when the
 *  cluster is unreachable at startup — callers translate that to 503/degraded. */
@Configuration
public class ElasticsearchConfig {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchConfig.class);

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(AppProperties props, ObjectMapper objectMapper) {
        URI uri = URI.create(props.getElasticsearchUrl());
        return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
                .setRequestConfigCallback(b -> b
                        .setConnectTimeout(5000)
                        .setSocketTimeout(10000))
                .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
        ElasticsearchClient client = new ElasticsearchClient(transport);
        try {
            boolean up = client.ping().value();
            log.info("elasticsearch reachable: {}", up);
        } catch (Exception e) {
            log.warn("elasticsearch unreachable at startup (degraded mode): {}",
                    e.getMessage());
        }
        return client;
    }
}
