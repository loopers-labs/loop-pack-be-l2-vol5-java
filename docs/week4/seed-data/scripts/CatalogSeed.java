import net.datafaker.Faker;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/** Reproducible CSV generation only; no entities, JPA, database or application startup. */
public class CatalogSeed {
    private static final String TIME = "2026-01-01 00:00:00.000000";
    private static final int BRANDS = 100;
    private static final int USERS = 10_000;
    private static final int PRODUCTS = 10_000;

    public static void main(String[] args) throws Exception {
        if (args.length > 2 && args[2].equals("skewed-1m")) {
            SkewedCatalogSeed.generate(Path.of(args[0]), Long.parseLong(args[1]));
            return;
        }
        Path directory = Path.of(args[0]);
        long seed = Long.parseLong(args[1]);
        if (Files.exists(directory)) throw new IllegalStateException("Output already exists: " + directory);
        Files.createDirectories(directory);
        Faker faker = new Faker(Locale.ENGLISH, new Random(seed));
        // Independent streams keep names, prices, stocks and relations reproducible separately.
        Random prices = new Random(seed + 1);
        Random stocks = new Random(seed + 2);
        Random likeCounts = new Random(seed + 3);
        Random likeUsers = new Random(seed + 4);
        Set<String> brandNames = new HashSet<>();
        Set<String> productNames = new HashSet<>();
        try (var out = csv(directory, "brands.csv", "id,name,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= BRANDS; id++) {
                String name = name(faker.company().name(), id, 50);
                if (!brandNames.add(name)) throw new IllegalStateException("Duplicate brand");
                row(out, id, name, TIME, TIME, null);
            }
        }
        try (var out = csv(directory, "users.csv", "id,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= USERS; id++) row(out, id, TIME, TIME, null);
        }
        long likeId = 0;
        try (var products = csv(directory, "products.csv", "id,brand_id,name,price,created_at,updated_at,deleted_at");
             var stock = csv(directory, "stocks.csv", "id,product_id,quantity,created_at,updated_at,deleted_at");
             var likes = csv(directory, "likes.csv", "id,user_id,product_id,created_at,updated_at,deleted_at")) {
            for (int id = 1; id <= PRODUCTS; id++) {
                int brandId = (id - 1) % BRANDS + 1;
                String name = name(faker.commerce().productName(), id, 100);
                if (!productNames.add(name)) throw new IllegalStateException("Duplicate product");
                long price = prices.nextLong(1, 100_000_000);
                int quantity = stocks.nextInt(100_000_000);
                row(products, id, brandId, name, price, TIME, TIME, null);
                row(stock, id, id, quantity, TIME, TIME, null);
                int count = likeCounts.nextInt(201);
                Set<Integer> selected = new HashSet<>();
                List<Integer> ordered = new ArrayList<>();
                while (ordered.size() < count) {
                    int userId = likeUsers.nextInt(USERS) + 1;
                    if (selected.add(userId)) ordered.add(userId);
                }
                for (int userId : ordered) row(likes, ++likeId, userId, id, TIME, TIME, null);
            }
        }
        Files.writeString(directory.resolve("generation.json"), """
            {
              "dataset": "%s",
              "seed": %d,
              "reference_time_utc": "%s",
              "datafaker_version": "2.5.1",
              "locale": "en",
              "generator_version": 1,
              "price": {"min": 1, "max": 99999999, "distribution": "uniform"},
              "stock": {"min": 0, "max": 99999999, "distribution": "uniform"},
              "likes_per_product": {"min": 0, "max": 200, "distribution": "uniform"},
              "brand_distribution": "uniform",
              "brands": 100, "users": 10000, "products": 10000, "stocks": 10000,
              "likes": %d
            }
            """.formatted(directory.getFileName(), seed, TIME, likeId));
        System.out.println("CSV generated: " + directory + ", likes=" + likeId);
    }

    static String name(String text, int id, int limit) {
        String suffix = "-" + id;
        String base = text.trim();
        int end = Math.min(base.length(), limit - suffix.length());
        if (end > 0 && Character.isHighSurrogate(base.charAt(end - 1))) end--;
        String name = base.substring(0, end).trim() + suffix;
        if (!name.equals(name.trim()) || name.isEmpty() || name.length() > limit) {
            throw new IllegalArgumentException("Invalid generated name");
        }
        return name;
    }

    static BufferedWriter csv(Path dir, String name, String header) throws Exception {
        BufferedWriter out = Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8);
        out.write(header + "\n");
        return out;
    }

    static void row(BufferedWriter out, Object... values) throws Exception {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.write(',');
            if (values[i] == null) {
                // An empty deleted_at field means NULL; the loader uses NULLIF.
            } else if (values[i] instanceof Number) {
                out.write(values[i].toString());
            } else {
                String value = values[i].toString().replace("\"", "\"\"");
                out.write('"');
                out.write(value);
                out.write('"');
            }
        }
        out.write('\n');
    }
}
