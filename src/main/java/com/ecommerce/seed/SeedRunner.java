package com.ecommerce.seed;

import com.ecommerce.config.AppProperties;
import com.ecommerce.model.User;
import com.ecommerce.mongo.ProductRepository;
import com.ecommerce.repository.OrderItemRepository;
import com.ecommerce.repository.OrderRepository;
import com.ecommerce.repository.UserRepository;
import com.ecommerce.service.Money;
import com.ecommerce.service.Money.OrderLine;
import com.ecommerce.service.OrderTxService;
import com.ecommerce.service.SearchService;
import com.ecommerce.service.SyncService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CLI seed: `java -jar app.jar --seed [--reset]` or `--reindex`.
 *  Deterministic (seed=42): 8 users, 26 products, 44 orders, wireless fixtures,
 *  snapshot mismatch (Wireless Mouse -> Pro in Mongo only), full ES reindex. */
@Component
public class SeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

    private static final List<String[]> USERS = List.of(
            new String[]{"John Doe", "john.doe@example.com"},
            new String[]{"Jane Smith", "jane.smith@example.com"},
            new String[]{"Wendy Wireless", "wendy.wireless@example.com"},
            new String[]{"Alex Rivera", "alex.rivera@example.com"},
            new String[]{"Sam Patel", "sam.patel@example.com"},
            new String[]{"Casey Nguyen", "casey.nguyen@example.com"},
            new String[]{"Morgan Lee", "morgan.lee@example.com"},
            new String[]{"Riley Brooks", "riley.brooks@example.com"});

    private final ApplicationContext ctx;
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OrderTxService orderTx;
    private final ProductRepository products;
    private final MongoTemplate mongo;
    private final SearchService search;
    private final SyncService sync;
    private final AppProperties props;

    public SeedRunner(ApplicationContext ctx, JdbcTemplate jdbc, UserRepository users,
                      OrderRepository orders, OrderItemRepository items, OrderTxService orderTx,
                      ProductRepository products, MongoTemplate mongo, SearchService search,
                      SyncService sync, AppProperties props) {
        this.ctx = ctx; this.jdbc = jdbc; this.users = users; this.orders = orders;
        this.items = items; this.orderTx = orderTx; this.products = products;
        this.mongo = mongo; this.search = search; this.sync = sync; this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean seed = args.containsOption("seed");
        boolean reindex = args.containsOption("reindex");
        if (!seed && !reindex) return; // normal server boot
        boolean reset = args.containsOption("reset");
        int exit;
        try {
            if (reindex && !seed) {
                exit = doReindex();
            } else {
                exit = doSeed(reset);
            }
        } catch (Exception e) {
            log.error("seed failed", e);
            System.out.println("SEED FAILED: " + e.getMessage());
            exit = 1;
        }
        System.exit(exit);
    }

    private int doReindex() throws Exception {
        long n = sync.reindexAll();
        System.out.println("reindexed " + n + " orders into '" + props.getEsIndex() + "'");
        return 0;
    }

    private int doSeed(boolean reset) throws Exception {
        products.ensureIndexes();
        if (reset) {
            jdbc.execute("TRUNCATE outbox, order_items, orders, sync_state, users RESTART IDENTITY CASCADE");
            products.deleteAll();
            products.ensureIndexes();
            search.deleteIndex(props.getEsIndex());
            log.info("demo data wiped (reset)");
        }
        search.ensureIndex();

        Map<Integer, Long> userMap = seedUsers();
        List<SeedPlan.ProductRef> catalog = seedProducts();
        seedOrders(userMap, catalog);
        applySnapshotMismatch();

        long indexed = sync.reindexAll();
        System.out.println("reindexed " + indexed + " orders");
        verify(catalog);
        System.out.println("seed verification passed");
        return 0;
    }

    // ------------------------------------------------------------------ pg ---
    private Map<Integer, Long> seedUsers() {
        Map<Integer, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < USERS.size(); i++) {
            String name = USERS.get(i)[0], email = USERS.get(i)[1];
            User u = users.findByEmail(email).orElseGet(() -> {
                User nu = new User();
                nu.setName(name);
                nu.setEmail(email);
                return users.save(nu);
            });
            map.put(i, u.getId());
        }
        return map;
    }

    // ---------------------------------------------------------------- mongo ---
    private static Map<String, Object> product(String sku, String title, double price,
                                               String category, List<String> tags,
                                               Map<String, Object> attributes,
                                               List<Map<String, Object>> variants) {
        return product(sku, title, price, category, tags, attributes, variants, true);
    }

    private static Map<String, Object> product(String sku, String title, double price,
                                               String category, List<String> tags,
                                               Map<String, Object> attributes,
                                               List<Map<String, Object>> variants,
                                               boolean active) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sku", sku);
        p.put("title", title);
        p.put("description", title + " — quality " + category + " product.");
        p.put("price", price);
        p.put("category", category);
        p.put("tags", tags);
        p.put("attributes", attributes);
        p.put("variants", variants);
        p.put("active", active);
        p.put("updated_at", Instant.now());
        return p;
    }

    private static Map<String, Object> v(String sku, String color, int stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", sku); m.put("color", color); m.put("stock", stock);
        return m;
    }

    private static List<Map<String, Object>> catalogDocs() {
        List<Map<String, Object>> c = new ArrayList<>();
        // peripherals (7)
        c.add(product("WM-001", "Wireless Mouse", 50.16, "peripherals",
                List.of("wireless", "usb", "office"),
                Map.of("color", "black", "dpi", 1600, "battery", "AA"),
                List.of(v("WM-001-BLK", "black", 40), v("WM-001-WHT", "white", 12))));
        c.add(product("MK-101", "Mechanical Keyboard", 100.34, "peripherals",
                List.of("wired", "rgb", "gaming"),
                Map.of("switch", "brown", "layout", "TKL", "backlight", true),
                List.of(v("MK-101-BRN", "black", 25))));
        c.add(product("WK-102", "Wireless Keyboard", 65.00, "peripherals",
                List.of("wireless", "bluetooth"),
                Map.of("layout", "full", "battery", "rechargeable"),
                List.of(v("WK-102-BLK", "black", 30))));
        c.add(product("HUB-201", "USB-C Hub 7-in-1", 45.00, "peripherals",
                List.of("usb-c", "hub", "laptop"),
                Map.of("ports", 7, "hdmi", "4K"),
                List.of(v("HUB-201-GRY", "gray", 50))));
        c.add(product("MP-202", "Ergonomic Mouse Pad", 18.99, "peripherals",
                List.of("office", "desk"),
                Map.of("size", "XL", "material", "rubber"),
                List.of(v("MP-202-BLK", "black", 100))));
        c.add(product("CAM-203", "Webcam HD 1080p", 55.50, "peripherals",
                List.of("video", "usb"),
                Map.of("resolution", "1080p", "microphone", true),
                List.of(v("CAM-203-BLK", "black", 35))));
        c.add(product("MON-204", "4K Monitor 27-inch", 249.99, "peripherals",
                List.of("display", "4k", "usb-c"),
                Map.of("size", "27in", "panel", "IPS"),
                List.of(v("MON-204-BLK", "black", 15))));
        // audio (6)
        c.add(product("EB-301", "Wireless Earbuds", 79.99, "audio",
                List.of("wireless", "bluetooth", "audio"),
                Map.of("battery_hours", 24, "anc", false),
                List.of(v("EB-301-WHT", "white", 60), v("EB-301-BLK", "black", 45))));
        c.add(product("NCH-302", "Noise Cancelling Headphones", 199.00, "audio",
                List.of("wireless", "anc", "premium"),
                Map.of("battery_hours", 40, "anc", true),
                List.of(v("NCH-302-BLK", "black", 20))));
        c.add(product("SPK-303", "Bluetooth Speaker Mini", 35.75, "audio",
                List.of("wireless", "bluetooth", "speaker"),
                Map.of("battery_hours", 12, "waterproof", "IPX5"),
                List.of(v("SPK-303-BLU", "blue", 40))));
        c.add(product("MIC-304", "USB Microphone Pro", 89.00, "audio",
                List.of("usb", "streaming"),
                Map.of("pattern", "cardioid", "mount", "desk"),
                List.of(v("MIC-304-BLK", "black", 28))));
        c.add(product("EP-305", "Wired Earphones", 12.99, "audio",
                List.of("wired", "budget"),
                Map.of("connector", "3.5mm"),
                List.of(v("EP-305-WHT", "white", 120))));
        c.add(product("SB-306", "Soundbar Slim", 129.99, "audio",
                List.of("tv", "hdmi", "audio"),
                Map.of("channels", "2.1", "hdmi_arc", true),
                List.of(v("SB-306-BLK", "black", 18))));
        // cables (6)
        c.add(product("CBL-401", "USB-C to USB-C Cable 2m", 14.99, "cables",
                List.of("usb-c", "charging"),
                Map.of("length", "2m", "wattage", 100),
                List.of(v("CBL-401-BLK", "black", 200))));
        c.add(product("HDMI-402", "HDMI 2.1 Cable", 19.99, "cables",
                List.of("hdmi", "4k"),
                Map.of("length", "2m", "version", "2.1"),
                List.of(v("HDMI-402-BLK", "black", 150))));
        c.add(product("WCP-403", "Wireless Charging Pad", 29.99, "cables",
                List.of("wireless", "charging", "qi"),
                Map.of("wattage", 15),
                List.of(v("WCP-403-WHT", "white", 70))));
        c.add(product("LGT-404", "Lightning Cable 1m", 11.50, "cables",
                List.of("apple", "charging"),
                Map.of("length", "1m", "mfi", true),
                List.of(v("LGT-404-WHT", "white", 180))));
        c.add(product("DP-405", "DisplayPort Cable", 22.00, "cables",
                List.of("display", "pc"),
                Map.of("length", "1.8m", "version", "1.4"),
                List.of(v("DP-405-BLK", "black", 90))));
        c.add(product("ETH-406", "Ethernet Cable Cat6 5m", 16.75, "cables",
                List.of("network", "wired"),
                Map.of("length", "5m", "category", "Cat6"),
                List.of(v("ETH-406-BLU", "blue", 110))));
        // office (6 active + 1 inactive)
        c.add(product("LAMP-501", "Desk Lamp LED", 32.50, "office",
                List.of("desk", "lighting"),
                Map.of("dimmable", true, "color_temp", "3000-6000K"),
                List.of(v("LAMP-501-WHT", "white", 55))));
        c.add(product("MST-502", "Monitor Stand Bamboo", 49.99, "office",
                List.of("desk", "ergonomic"),
                Map.of("material", "bamboo", "max_load_kg", 15),
                List.of(v("MST-502-NAT", "natural", 32))));
        c.add(product("PRS-503", "Wireless Presenter Remote", 39.95, "office",
                List.of("wireless", "presentation"),
                Map.of("range_m", 30, "laser", "red"),
                List.of(v("PRS-503-BLK", "black", 48))));
        c.add(product("LST-504", "Laptop Stand Aluminum", 42.00, "office",
                List.of("desk", "laptop"),
                Map.of("material", "aluminum", "adjustable", true),
                List.of(v("LST-504-SLV", "silver", 42))));
        c.add(product("ORG-505", "Desk Organizer Set", 24.99, "office",
                List.of("desk", "storage"),
                Map.of("pieces", 5),
                List.of(v("ORG-505-GRY", "gray", 65))));
        c.add(product("CUSH-506", "Office Chair Cushion", 27.50, "office",
                List.of("comfort", "chair"),
                Map.of("material", "memory foam"),
                List.of(v("CUSH-506-BLK", "black", 80))));
        c.add(product("CRT-507", "Old CRT Monitor", 99.99, "office",
                List.of("vintage"),
                Map.of("size", "17in"),
                List.of(v("CRT-507-BGE", "beige", 2)), false));
        return c;
    }

    private List<SeedPlan.ProductRef> seedProducts() {
        List<SeedPlan.ProductRef> refs = new ArrayList<>();
        for (Map<String, Object> p : catalogDocs()) {
            Map<String, Object> existing = products.findBySku((String) p.get("sku"));
            if (existing == null) existing = products.create(p);
            @SuppressWarnings("unchecked")
            List<String> tags = (List<String>) existing.get("tags");
            refs.add(new SeedPlan.ProductRef((String) existing.get("sku"),
                    (String) existing.get("title"),
                    ((Number) existing.get("price")).doubleValue(),
                    tags, Boolean.TRUE.equals(existing.get("active"))));
        }
        return refs;
    }

    // ---------------------------------------------------------------- orders ---
    private void seedOrders(Map<Integer, Long> userMap, List<SeedPlan.ProductRef> catalog) {
        if (orders.count() > 0) {
            log.info("orders already seeded; skipping");
            return;
        }
        // mongo id lookup by sku
        Map<String, String> mongoIds = new LinkedHashMap<>();
        for (SeedPlan.ProductRef p : catalog) {
            Map<String, Object> doc = products.findBySku(p.sku());
            mongoIds.put(p.sku(), (String) doc.get("id"));
        }
        List<SeedPlan.PlannedOrder> plan = SeedPlan.buildOrderPlan(catalog);
        List<String> problems = SeedPlan.checkPlan(plan, catalog);
        if (!problems.isEmpty()) throw new IllegalStateException("seed plan violates rules: " + problems);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (SeedPlan.PlannedOrder o : plan) {
            List<OrderLine> lines = new ArrayList<>();
            for (SeedPlan.Line l : o.lines) {
                SeedPlan.ProductRef p = catalog.get(l.productIdx());
                lines.add(new OrderLine(mongoIds.get(p.sku()), p.title(), l.quantity(),
                        BigDecimal.valueOf(p.price()).setScale(2, RoundingMode.HALF_UP)));
            }
            OffsetDateTime orderDate = now.minusDays(o.daysAgo)
                    .minusMinutes((o.daysAgo * 37L) % 1440);
            com.ecommerce.model.Order order = orderTx.createOrder(
                    userMap.get(o.userIdx), lines, null, null, orderDate, orderDate)
                    .orElseThrow();
            if (!"PENDING".equals(o.status)) {
                orderTx.updateStatus(order.getId(), o.status, null);
            }
        }
        log.info("orders seeded: {}", plan.size());
    }

    private void applySnapshotMismatch() {
        mongo.updateFirst(
                Query.query(Criteria.where("sku").is("WM-001")),
                new Update().set("title", "Wireless Mouse Pro")
                        .set("price", 59.99)
                        .set("updated_at", Instant.now()),
                ProductRepository.COLLECTION);
        log.info("snapshot mismatch applied: WM-001 -> Wireless Mouse Pro @ 59.99");
    }

    // ---------------------------------------------------------------- verify ---
    private void verify(List<SeedPlan.ProductRef> catalog) {
        long nUsers = users.count();
        long nOrders = orders.count();
        long nItems = items.count();
        long nActive = products.countActive(true);
        long nInactive = products.countActive(false);
        Map<String, Object> wm = products.findBySku("WM-001");
        Long esDocs = search.countDocs();

        Map<String, Object> hist = jdbc.queryForMap(
                "SELECT title, unit_price FROM order_items WHERE title = 'Wireless Mouse' LIMIT 1");

        System.out.printf("users=%d products(active)=%d inactive=%d orders=%d items=%d es_docs=%s%n",
                nUsers, nActive, nInactive, nOrders, nItems, esDocs);
        System.out.printf("mongo WM-001 title=%s price=%s%n", wm.get("title"), wm.get("price"));
        System.out.printf("historical PG snapshot intact: title=%s unit_price=%s%n",
                hist.get("title"), hist.get("unit_price"));

        assertTrue(nUsers == 8, "expected 8 users");
        assertTrue(nActive >= 24, "expected >=24 active products");
        assertTrue(nInactive >= 1, "expected >=1 inactive product");
        assertTrue(nOrders == 44, "expected 44 orders");
        assertTrue(nItems >= 80, "expected >=80 items");
        assertTrue("Wireless Mouse Pro".equals(wm.get("title"))
                && Math.abs(((Number) wm.get("price")).doubleValue() - 59.99) < 0.001,
                "catalog rename missing");
        assertTrue("Wireless Mouse".equals(hist.get("title"))
                && Math.abs(((Number) hist.get("unit_price")).doubleValue() - 50.16) < 0.001,
                "historical snapshot was rewritten!");
        if (esDocs != null) assertTrue(esDocs == nOrders, "ES docs != PG orders");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new IllegalStateException("seed verification failed: " + msg);
    }
}
