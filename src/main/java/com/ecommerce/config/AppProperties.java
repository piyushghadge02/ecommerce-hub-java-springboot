package com.ecommerce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Application settings bound from application.properties / env vars.
 *  Spring's relaxed binding maps e.g. SYNC_STRATEGY -> syncStrategy. */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String elasticsearchUrl = "http://localhost:9200";
    private String esIndex = "orders";
    /** dual_write | outbox | polling */
    private String syncStrategy = "dual_write";
    private int outboxPollSeconds = 2;
    private int pollingIntervalSeconds = 15;
    private int outboxMaxAttempts = 10;
    private int repairLoopSeconds = 10;
    private String corsOrigins = "http://localhost:5173,http://127.0.0.1:5173";

    public String getElasticsearchUrl() { return elasticsearchUrl; }
    public void setElasticsearchUrl(String v) { this.elasticsearchUrl = v; }
    public String getEsIndex() { return esIndex; }
    public void setEsIndex(String v) { this.esIndex = v; }
    public String getSyncStrategy() {
        return syncStrategy == null ? "dual_write" : syncStrategy.trim().toLowerCase();
    }
    public void setSyncStrategy(String v) { this.syncStrategy = v; }
    public int getOutboxPollSeconds() { return outboxPollSeconds; }
    public void setOutboxPollSeconds(int v) { this.outboxPollSeconds = v; }
    public int getPollingIntervalSeconds() { return pollingIntervalSeconds; }
    public void setPollingIntervalSeconds(int v) { this.pollingIntervalSeconds = v; }
    public int getOutboxMaxAttempts() { return outboxMaxAttempts; }
    public void setOutboxMaxAttempts(int v) { this.outboxMaxAttempts = v; }
    public int getRepairLoopSeconds() { return repairLoopSeconds; }
    public void setRepairLoopSeconds(int v) { this.repairLoopSeconds = v; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String v) { this.corsOrigins = v; }
}
