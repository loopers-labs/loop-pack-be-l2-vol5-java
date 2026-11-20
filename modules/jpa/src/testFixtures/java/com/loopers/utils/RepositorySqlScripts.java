package com.loopers.utils;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RepositorySqlScripts {

    private RepositorySqlScripts() {
    }

    public static void run(DataSource dataSource, String... repositoryRelativePaths) {
        var populator = new ResourceDatabasePopulator();
        for (String relativePath : repositoryRelativePaths) {
            populator.addScript(new FileSystemResource(resolve(relativePath)));
        }
        populator.execute(dataSource);
    }

    private static Path resolve(String relativePath) {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("SQL script not found from working directory: " + relativePath);
    }
}
