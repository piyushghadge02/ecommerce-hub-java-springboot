package com.ecommerce.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Money + time helpers mirroring the Python implementation. */
public final class Money {

    private Money() {}

    /** Quantize to cents, HALF_UP — like Python's _money(). */
    public static BigDecimal money(Number value) {
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal lineTotal(BigDecimal unitPrice, int quantity) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    public static OffsetDateTime truncateToSecond(OffsetDateTime dt) {
        return dt == null ? null : dt.truncatedTo(ChronoUnit.SECONDS);
    }

    /** Validated checkout line: (productId, title snapshot, quantity, unit price). */
    public record OrderLine(String productId, String title, int quantity, BigDecimal unitPrice) {}
}
