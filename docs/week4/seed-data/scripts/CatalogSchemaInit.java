import com.loopers.CommerceApiApplication;
import java.sql.DriverManager;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** Creates tables only in an explicitly empty perf_* database. */
public class CatalogSchemaInit {
    public static void main(String[] args) throws Exception {
        String database = env("PERF_DB_NAME", "perf_uniform_10k_v1");
        if (!database.matches("perf_[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Only perf_* database names are allowed");
        }
        String url = "jdbc:mysql://" + env("PERF_DB_HOST", "127.0.0.1") + ":"
            + env("PERF_DB_PORT", "13307") + "/" + database;
        String user = env("PERF_DB_USER", "application");
        String password = env("PERF_DB_PASSWORD", "application");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select count(*) from information_schema.tables where table_schema=database()")) {
            rows.next();
            if (rows.getInt(1) != 0) {
                throw new IllegalStateException("Database already has tables; refusing to recreate: " + database);
            }
        }
        try (var context = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("local", "perf")
            .run("--server.port=0", "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--datasource.mysql-jpa.main.jdbc-url=" + url,
                "--datasource.mysql-jpa.main.username=" + user)) {
            System.out.println("Created schema in " + database + "; subsequent runs use ddl-auto=none");
        }
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
