package com.ecommerce.seed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Deterministic order-plan builder (seed=42), ported from the Python seed.
 *  Pure functions — unit-testable without any database. */
public final class SeedPlan {

    public static final int SEED = 42;
    public static final int WENDY_IDX = 2;

    public record ProductRef(String sku, String title, double price,
                             List<String> tags, boolean active) {}

    public record Line(int productIdx, int quantity) {}

    public static class PlannedOrder {
        public int userIdx;
        public final List<Line> lines = new ArrayList<>();
        public String status;
        public int daysAgo;
    }

    private SeedPlan() {}

    public static List<PlannedOrder> buildOrderPlan(List<ProductRef> products) {
        Random rng = new Random(SEED);
        java.util.Map<String, Integer> sku = new java.util.HashMap<>();
        for (int i = 0; i < products.size(); i++) sku.put(products.get(i).sku(), i);

        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < products.size(); i++) if (products.get(i).active()) active.add(i);
        List<Integer> wireless = new ArrayList<>();
        for (int i : active) {
            ProductRef p = products.get(i);
            boolean w = p.title().toLowerCase().contains("wireless")
                    || p.tags().stream().anyMatch(t -> t.equalsIgnoreCase("wireless"));
            if (w) wireless.add(i);
        }
        List<Integer> cheap = new ArrayList<>();
        List<Integer> mid = new ArrayList<>();
        for (int i : active) {
            double pr = products.get(i).price();
            if (pr < 25) cheap.add(i);
            else if (pr <= 99) mid.add(i);
        }

        int WM = sku.get("WM-001"), MK = sku.get("MK-101");
        int NCH = sku.get("NCH-302"), SB = sku.get("SB-306"), MON = sku.get("MON-204");
        int EP = sku.get("EP-305");

        List<PlannedOrder> plan = new ArrayList<>();

        for (int u = 0; u < 8; u++)
            for (int k = 0; k < 2; k++)
                plan.add(order(u, randomLines(rng, active, pick(rng, 2, 2, 3))));
        for (int k = 0; k < 3; k++) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(WM, 1 + rng.nextInt(2)));
            lines.addAll(randomLines(rng, active, rng.nextInt(2)));
            plan.add(order(WENDY_IDX, lines));
        }
        for (int i = 0; i < 5; i++)
            plan.add(order(rng.nextInt(8), List.of(new Line(cheap.get(i % cheap.size()), 1))));
        for (int i = 0; i < 5; i++) {
            int p = mid.get(i % mid.size());
            int qty = products.get(p).price() * 2 > 150 ? 1 : 2;
            plan.add(order(rng.nextInt(8), List.of(new Line(p, qty))));
        }
        int[][] premium = {{NCH, 2}, {SB, 2}, {MON, 1}, {MK, 3}};
        for (int[] pr : premium)
            plan.add(order(rng.nextInt(8), List.of(new Line(pr[0], pr[1]))));
        plan.add(order(rng.nextInt(8), List.of(new Line(NCH, 1), new Line(EP, 1))));
        for (int k = 0; k < 10; k++)
            plan.add(order(rng.nextInt(8), randomLines(rng, active, pick(rng, 2, 2, 3))));

        if (plan.size() != 44) throw new IllegalStateException("plan size " + plan.size());

        // fixtures: 8x WM, 3x MK, 2x both. Slots 16..18 already have WM (Wendy).
        List<Integer> pool = new ArrayList<>();
        for (int i = 34; i < 44; i++) pool.add(i);
        Collections.shuffle(pool, rng);
        List<Integer> wmSlots = new ArrayList<>(List.of(16, 17, 18));
        wmSlots.addAll(pool.subList(0, 5));
        List<Integer> mkSlots = pool.subList(5, 8);
        List<Integer> bothSlots = pool.subList(0, 2);
        for (int s : wmSlots)
            if (plan.get(s).lines.stream().noneMatch(l -> l.productIdx() == WM))
                plan.get(s).lines.add(new Line(WM, 1));
        for (int s : mkSlots) plan.get(s).lines.add(new Line(MK, 1));
        for (int s : bothSlots)
            if (plan.get(s).lines.stream().noneMatch(l -> l.productIdx() == MK))
                plan.get(s).lines.add(new Line(MK, 1));

        for (int i = 0; i < plan.size(); i++)
            plan.get(i).status = i < 15 ? "PENDING" : (i < 30 ? "PROCESSING" : "SHIPPED");

        List<Integer> days = new ArrayList<>(List.of(1, 2, 4, 5, 6));
        for (int i = 0; i < 39; i++) days.add(8 + (i * 82) / 38);
        Collections.shuffle(days, rng);
        for (int i = 0; i < plan.size(); i++) plan.get(i).daysAgo = days.get(i);

        return plan;
    }

    private static PlannedOrder order(int userIdx, List<Line> lines) {
        PlannedOrder o = new PlannedOrder();
        o.userIdx = userIdx;
        o.lines.addAll(lines);
        return o;
    }

    private static int pick(Random rng, int... choices) {
        return choices[rng.nextInt(choices.length)];
    }

    private static List<Line> randomLines(Random rng, List<Integer> active, int n) {
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < n; i++)
            out.add(new Line(active.get(rng.nextInt(active.size())), 1 + rng.nextInt(3)));
        return out;
    }

    /** Returns violated §9 rules; empty means all satisfied. */
    public static List<String> checkPlan(List<PlannedOrder> plan, List<ProductRef> products) {
        List<String> problems = new ArrayList<>();
        java.util.Map<String, Integer> sku = new java.util.HashMap<>();
        for (int i = 0; i < products.size(); i++) sku.put(products.get(i).sku(), i);
        int WM = sku.get("WM-001"), MK = sku.get("MK-101");

        if (plan.size() != 44) problems.add("expected 44 orders, got " + plan.size());
        int nItems = plan.stream().mapToInt(o -> o.lines.size()).sum();
        if (nItems < 80) problems.add("expected >=80 items, got " + nItems);
        for (String st : List.of("PENDING", "PROCESSING", "SHIPPED")) {
            long c = plan.stream().filter(o -> st.equals(o.status)).count();
            if (c < 10) problems.add("status " + st + ": " + c + " < 10");
        }
        if (plan.stream().filter(o -> o.daysAgo > 60).count() < 5)
            problems.add("need >=5 orders older than 60 days");
        if (plan.stream().filter(o -> o.daysAgo <= 7).count() < 5)
            problems.add("need >=5 orders within last 7 days");
        if (plan.stream().mapToInt(o -> o.daysAgo).max().orElse(0) < 85)
            problems.add("dates do not spread over ~90 days");
        long under30 = 0, band = 0, over200 = 0;
        for (PlannedOrder o : plan) {
            BigDecimal t = total(o, products);
            if (t.compareTo(new BigDecimal("30")) < 0) under30++;
            else if (t.compareTo(new BigDecimal("150")) <= 0) band++;
            if (t.compareTo(new BigDecimal("200")) > 0) over200++;
        }
        if (under30 < 5) problems.add("need >=5 orders under $30");
        if (band < 5) problems.add("need >=5 orders $30-$150");
        if (over200 < 5) problems.add("need >=5 orders over $200");
        for (int u = 0; u < 8; u++) {
            final int uu = u;
            if (plan.stream().filter(o -> o.userIdx == uu).count() < 2)
                problems.add("user " + u + " needs >=2 orders");
        }
        long wendyWireless = plan.stream().filter(o -> o.userIdx == WENDY_IDX
                && o.lines.stream().anyMatch(l -> isWireless(products.get(l.productIdx())))).count();
        if (wendyWireless < 3) problems.add("Wendy wireless orders: " + wendyWireless + " < 3");
        long wmOrders = plan.stream().filter(o -> o.lines.stream().anyMatch(l -> l.productIdx() == WM)).count();
        if (wmOrders < 8) problems.add("Wireless Mouse orders: " + wmOrders + " < 8");
        long mkOrders = plan.stream().filter(o -> o.lines.stream().anyMatch(l -> l.productIdx() == MK)).count();
        if (mkOrders < 3) problems.add("Mechanical Keyboard orders: " + mkOrders + " < 3");
        long both = plan.stream().filter(o -> o.lines.stream().anyMatch(l -> l.productIdx() == WM)
                && o.lines.stream().anyMatch(l -> l.productIdx() == MK)).count();
        if (both < 2) problems.add("orders with both products: " + both + " < 2");
        return problems;
    }

    private static boolean isWireless(ProductRef p) {
        return p.title().toLowerCase().contains("wireless")
                || p.tags().stream().anyMatch(t -> t.equalsIgnoreCase("wireless"));
    }

    static BigDecimal total(PlannedOrder o, List<ProductRef> products) {
        BigDecimal t = BigDecimal.ZERO;
        for (Line l : o.lines)
            t = t.add(BigDecimal.valueOf(products.get(l.productIdx()).price())
                    .multiply(BigDecimal.valueOf(l.quantity())));
        return t;
    }
}
