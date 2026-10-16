import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.datafaker.Faker;

/** Fixed marginal targets; synthetic within-group weights, not a fitted power law. */
public class SkewedCatalogSeed {
    private static final String TIME = "2026-01-01 00:00:00.000000";
    private static final int PRODUCTS = 1_000_000, USERS = 240_000;
    record Degree(int id, int count) {}

    static int[] degrees(int size, int total, int minimum, int maximum) {
        int[] values = new int[size];
        Arrays.fill(values, minimum);
        int remaining = total - size * minimum;
        if (remaining < 0 || (long) size * maximum < total) throw new IllegalArgumentException();
        // Allocate monotonically by rank, cap, and repeat until all integer units are placed.
        while (remaining > 0) {
            double weight = 0;
            for (int i = 0; i < size; i++) if (values[i] < maximum) weight += 1 / Math.sqrt(i + 1);
            int budget = remaining;
            for (int i = 0; i < size; i++) {
                if (values[i] == maximum) continue;
                int amount = Math.min(maximum - values[i], (int) (budget / Math.sqrt(i + 1) / weight));
                values[i] += amount;
                remaining -= amount;
            }
            if (budget == remaining) {
                for (int i = 0; i < size && remaining > 0; i++) {
                    if (values[i] < maximum) { values[i]++; remaining--; }
                }
            }
        }
        return values;
    }

    static int[] shuffledIds(int size, long seed) {
        int[] ids = new int[size];
        for (int i = 0; i < size; i++) ids[i] = i + 1;
        Random random = new Random(seed);
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1), temp = ids[i]; ids[i] = ids[j]; ids[j] = temp;
        }
        return ids;
    }

    static List<Degree> targets(int[] ids, int[][] groups) {
        List<Degree> result = new ArrayList<>();
        int offset = 0;
        for (int[] g : groups) {
            for (int count : degrees(g[0], g[1], g[2], g[3])) result.add(new Degree(ids[offset++], count));
        }
        result.sort(Comparator.comparingInt(Degree::count).reversed().thenComparingInt(Degree::id));
        return result;
    }

    public static void generate(Path dir, long seed) throws Exception {
        if (Files.exists(dir)) throw new IllegalStateException("Output already exists: " + dir);
        Files.createDirectories(dir);
        Faker faker = new Faker(Locale.ENGLISH, new Random(seed));
        Random prices = new Random(seed + 1), stocks = new Random(seed + 2);
        int[] brandForProduct = new int[PRODUCTS + 1];
        int[] productIds = shuffledIds(PRODUCTS, seed + 5);
        int offset = 0;
        for (int b = 1; b <= 1000; b++) {
            int count = b <= 100 ? 5000 : b <= 400 ? 1000 : b <= 600 ? 334 : 333;
            for (int i = 0; i < count; i++) brandForProduct[productIds[offset++]] = b;
        }
        if (offset != PRODUCTS) throw new IllegalStateException("Brand total");
        try (var out = CatalogSeed.csv(dir, "brands.csv", "id,name,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= 1000; id++) CatalogSeed.row(out, id, CatalogSeed.name(faker.company().name(), id, 50), TIME, TIME, null);
        }
        try (var out = CatalogSeed.csv(dir, "users.csv", "id,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= USERS; id++) CatalogSeed.row(out, id, TIME, TIME, null);
        }
        try (var products = CatalogSeed.csv(dir, "products.csv", "id,brand_id,name,price,created_at,updated_at,deleted_at");
             var stock = CatalogSeed.csv(dir, "stocks.csv", "id,product_id,quantity,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= PRODUCTS; id++) {
                CatalogSeed.row(products, id, brandForProduct[id], CatalogSeed.name(faker.commerce().productName(), id, 100), prices.nextLong(1, 100_000_000), TIME, TIME, null);
                CatalogSeed.row(stock, id, id, stocks.nextInt(100_000_000), TIME, TIME, null);
                if (id % 100000 == 0) System.out.println("Generated products: " + id);
            }
        }
        List<Degree> productTargets = targets(shuffledIds(PRODUCTS, seed + 3), new int[][] {
            {10000, 269100, 12, 1000}, {90000, 310500, 2, 12}, {110000, 110400, 1, 2}
        });
        List<Degree> userTargets = targets(shuffledIds(USERS, seed + 4), new int[][] {
            {48000, 614100, 3, 500}, {45600, 75900, 1, 3}
        });
        PriorityQueue<Degree> heap = new PriorityQueue<>(Comparator.comparingInt(Degree::count).reversed().thenComparingInt(Degree::id));
        heap.addAll(userTargets);
        long likeId = 0;
        try (var out = CatalogSeed.csv(dir, "likes.csv", "id,user_id,product_id,created_at,updated_at,deleted_at")) {
            for (Degree product : productTargets) {
                List<Degree> chosen = new ArrayList<>();
                for (int i = 0; i < product.count(); i++) {
                    Degree user = heap.poll();
                    if (user == null) throw new IllegalStateException("Non-graphical degree targets");
                    CatalogSeed.row(out, ++likeId, user.id(), product.id(), TIME, TIME, null);
                    if (user.count() > 1) chosen.add(new Degree(user.id(), user.count() - 1));
                }
                heap.addAll(chosen); // A user cannot be selected twice for the same product.
            }
        }
        if (likeId != 690000 || !heap.isEmpty()) throw new IllegalStateException("Like total");
        Files.writeString(dir.resolve("generation.json"), """
            {
              "dataset": "%s", "seed": %d,
              "reference_time_utc": "%s", "datafaker_version": "2.5.1", "locale": "en",
              "generator_version": 2, "profile": "skewed-1m",
              "price": {"min": 1, "max": 99999999, "distribution": "uniform"},
              "stock": {"min": 0, "max": 99999999, "distribution": "uniform"},
              "likes_per_product": {"distribution": "fixed-marginals", "arbitrary_cap": null},
              "brand_distribution": "tiered",
              "brands": 1000, "users": 240000, "products": 1000000, "stocks": 1000000, "likes": 690000,
              "targets": {"zero_products": 790000, "zero_users": 146400,
                "top_1_percent_product_likes": 269100, "top_10_percent_product_likes": 579600,
                "top_20_percent_user_likes": 614100},
              "within_group_weights": "inverse-square-root rank, bounded to keep group order; synthetic, not fitted",
              "pairing": "bipartite Havel-Hakimi, descending product degrees and highest remaining user degree",
              "product_degree_groups": [[10000,269100,12,1000],[90000,310500,2,12],[110000,110400,1,2]],
              "user_degree_groups": [[48000,614100,3,500],[45600,75900,1,3]]
            }
            """.formatted(dir.getFileName(), seed, TIME));
        System.out.println("CSV generated: " + dir + ", likes=" + likeId);
    }
}
