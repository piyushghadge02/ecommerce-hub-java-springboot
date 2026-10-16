package com.ecommerce.seed;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for the deterministic seed plan (no database needed). */
class SeedPlanTest {

    /** Mirror of the seed catalog SKUs/titles/prices for rule checking. */
    static List<SeedPlan.ProductRef> catalogRefs() {
        // sku, title, price, category, wireless?
        Object[][] defs = {
                {"WM-001", "Wireless Mouse", 50.16, "peripherals", true},
                {"MK-101", "Mechanical Keyboard", 100.34, "peripherals", false},
                {"WK-102", "Wireless Keyboard", 65.00, "peripherals", true},
                {"HUB-201", "USB-C Hub 7-in-1", 45.00, "peripherals", false},
                {"MP-202", "Ergonomic Mouse Pad", 18.99, "peripherals", false},
                {"CAM-203", "Webcam HD 1080p", 55.50, "peripherals", false},
                {"MON-204", "4K Monitor 27-inch", 249.99, "peripherals", false},
                {"EB-301", "Wireless Earbuds", 79.99, "audio", true},
                {"NCH-302", "Noise Cancelling Headphones", 199.00, "audio", true},
                {"SPK-303", "Bluetooth Speaker Mini", 35.75, "audio", true},
                {"MIC-304", "USB Microphone Pro", 89.00, "audio", false},
                {"EP-305", "Wired Earphones", 12.99, "audio", false},
                {"SB-306", "Soundbar Slim", 129.99, "audio", false},
                {"CBL-401", "USB-C to USB-C Cable 2m", 14.99, "cables", false},
                {"HDMI-402", "HDMI 2.1 Cable", 19.99, "cables", false},
                {"WCP-403", "Wireless Charging Pad", 29.99, "cables", true},
                {"LGT-404", "Lightning Cable 1m", 11.50, "cables", false},
                {"DP-405", "DisplayPort Cable", 22.00, "cables", false},
                {"ETH-406", "Ethernet Cable Cat6 5m", 16.75, "cables", false},
                {"LAMP-501", "Desk Lamp LED", 32.50, "office", false},
                {"MST-502", "Monitor Stand Bamboo", 49.99, "office", false},
                {"PRS-503", "Wireless Presenter Remote", 39.95, "office", true},
                {"LST-504", "Laptop Stand Aluminum", 42.00, "office", false},
                {"ORG-505", "Desk Organizer Set", 24.99, "office", false},
                {"CUSH-506", "Office Chair Cushion", 27.50, "office", false},
                {"CRT-507", "Old CRT Monitor", 99.99, "office", false},
        };
        List<SeedPlan.ProductRef> out = new ArrayList<>();
        for (Object[] d : defs) {
            boolean wireless = (boolean) d[4];
            List<String> tags = wireless ? List.of("wireless") : List.of("other");
            out.add(new SeedPlan.ProductRef((String) d[0], (String) d[1],
                    (double) d[2], tags, !"CRT-507".equals(d[0])));
        }
        // mark CRT-507 inactive
        return out;
    }

    @Test
    void planSatisfiesAllRules() {
        List<SeedPlan.ProductRef> catalog = catalogRefs();
        List<SeedPlan.PlannedOrder> plan = SeedPlan.buildOrderPlan(catalog);
        List<String> problems = SeedPlan.checkPlan(plan, catalog);
        assertTrue(problems.isEmpty(), "violations: " + problems);
    }

    @Test
    void planIsDeterministic() {
        List<SeedPlan.ProductRef> catalog = catalogRefs();
        List<SeedPlan.PlannedOrder> a = SeedPlan.buildOrderPlan(catalog);
        List<SeedPlan.PlannedOrder> b = SeedPlan.buildOrderPlan(catalog);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).userIdx, b.get(i).userIdx);
            assertEquals(a.get(i).status, b.get(i).status);
            assertEquals(a.get(i).daysAgo, b.get(i).daysAgo);
            assertEquals(a.get(i).lines.size(), b.get(i).lines.size());
        }
    }

    @Test
    void catalogMeetsProductRules() {
        List<SeedPlan.ProductRef> catalog = catalogRefs();
        long active = catalog.stream().filter(SeedPlan.ProductRef::active).count();
        assertTrue(active >= 24, "active products: " + active);
        assertTrue(catalog.stream().filter(p -> !p.active()).count() >= 1);
        Map<String, Long> byCat = new java.util.HashMap<>();
        for (SeedPlan.ProductRef p : catalog) {
            if (!p.active()) continue;
            String cat = p.sku().split("-")[0];
            byCat.merge(cat, 1L, Long::sum);
        }
        // categories derived from sku prefixes: WM,MK,WK,HUB,MP,CAM,MON / EB,NCH,SPK,MIC,EP,SB / CBL,HDMI,WCP,LGT,DP,ETH / LAMP,MST,PRS,LST,ORG,CUSH
        long wireless = catalog.stream().filter(p -> p.active()
                && (p.title().toLowerCase().contains("wireless")
                    || p.tags().contains("wireless"))).count();
        assertTrue(wireless >= 6, "wireless products: " + wireless);
        long cheap = catalog.stream().filter(p -> p.active() && p.price() < 25).count();
        long mid = catalog.stream().filter(p -> p.active() && p.price() >= 25 && p.price() <= 99).count();
        long premium = catalog.stream().filter(p -> p.active() && p.price() > 100).count();
        assertTrue(cheap >= 4 && mid >= 4 && premium >= 4,
                String.format("bands cheap=%d mid=%d premium=%d", cheap, mid, premium));
    }
}
