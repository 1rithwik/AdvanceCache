package com.example;

import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.google.common.cache.Cache;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import com.google.common.cache.CacheBuilder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public class AdvancedConcurrentDatabaseCachingBenchmark {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/testdb";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "Root";
    private static final int NUM_ELEMENTS = 100000;
    private static final int L1_CACHE_SIZE = 10000;
    private static final int L2_CACHE_SIZE = 100000;
    private static final int L2_CACHE_DURATION_MINUTES = 10;
    private static final int NUM_THREADS = Runtime.getRuntime().availableProcessors();
    private static final int BATCH_SIZE = 10000;

    private static Connection connection;
    private static Map<Integer, String> l1Cache;
    private static Cache<Integer, String> l2Cache;
    private static final Logger LOGGER = Logger.getLogger(AdvancedConcurrentDatabaseCachingBenchmark.class.getName());
    private static final ExecutorService executorService = Executors.newFixedThreadPool(NUM_THREADS);
    private static final Random random = new Random();

    public static void main(String[] args) {
        setupLogging();
        try {
            setupDatabase();
            setupCaches();

            List<BenchmarkResult> results = new ArrayList<>();
            results.add(new BenchmarkResult("Database Insert",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::databaseInsert)));
            results.add(new BenchmarkResult("Database Retrieve",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::databaseRetrieve)));
            results.add(new BenchmarkResult("L1 Cache Insert",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::l1CacheInsert)));
            results.add(new BenchmarkResult("L1 Cache Retrieve",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::l1CacheRetrieve)));
            results.add(new BenchmarkResult("L2 Cache Insert",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::l2CacheInsert)));
            results.add(new BenchmarkResult("L2 Cache Retrieve",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::l2CacheRetrieve)));
            results.add(new BenchmarkResult("Multilevel Cache Retrieve",
                    benchmarkConcurrent(AdvancedConcurrentDatabaseCachingBenchmark::multilevelCacheRetrieve)));

            analyzeAndLogResults(results);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "An error occurred during benchmark execution", e);
        } finally {
            try {
                if (connection != null) {
                    connection.close();
                }
                executorService.shutdown();
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, "Error closing database connection", e);
            }
        }
    }

    private static void setupLogging() {
        try {
            FileHandler fileHandler = new FileHandler("concurrent_benchmark_log.txt");
            SimpleFormatter formatter = new SimpleFormatter();
            fileHandler.setFormatter(formatter);
            LOGGER.addHandler(fileHandler);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error setting up logging", e);
        }
    }

    private static void setupDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            Statement statement = connection.createStatement();
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, value VARCHAR(255))");
            statement.close();
            LOGGER.info("Database setup completed successfully.");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error setting up database", e);
        }
    }

    private static void setupCaches() {
        l1Cache = new ConcurrentHashMap<>(L1_CACHE_SIZE);
        l2Cache = CacheBuilder.newBuilder()
                .maximumSize(L2_CACHE_SIZE)
                .expireAfterAccess(L2_CACHE_DURATION_MINUTES, TimeUnit.MINUTES)
                .build();
        LOGGER.info("Caches setup completed successfully.");
    }

    private static long benchmarkConcurrent(Supplier<Void> operation) throws InterruptedException, ExecutionException {
        long startTime = System.nanoTime();
        List<Future<Void>> futures = new ArrayList<>();

        for (int i = 0; i < NUM_THREADS; i++) {
            int startIndex = i * (NUM_ELEMENTS / NUM_THREADS);
            int endIndex = (i + 1) * (NUM_ELEMENTS / NUM_THREADS);
            futures.add(executorService.submit(() -> {
                IntStream.range(startIndex, endIndex).forEach(j -> operation.get());
                return null;
            }));
        }

        for (Future<Void> future : futures) {
            future.get();
        }

        long endTime = System.nanoTime();
        return endTime - startTime;
    }

    private static Void databaseInsert() {
        try {
            String sql = "INSERT INTO test_table (id, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)";
            PreparedStatement statement = connection.prepareStatement(sql);
            int id = random.nextInt(NUM_ELEMENTS);
            statement.setInt(1, id);
            statement.setString(2, "Value" + id);
            statement.executeUpdate();
            statement.close();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error in database insert", e);
        }
        return null;
    }

    private static Void databaseRetrieve() {
        try {
            String sql = "SELECT value FROM test_table WHERE id = ?";
            PreparedStatement statement = connection.prepareStatement(sql);
            int id = random.nextInt(NUM_ELEMENTS);
            statement.setInt(1, id);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                resultSet.getString("value");
            }
            resultSet.close();
            statement.close();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error in database retrieve", e);
        }
        return null;
    }

    private static Void l1CacheInsert() {
        int id = random.nextInt(NUM_ELEMENTS);
        l1Cache.put(id, "Value" + id);
        return null;
    }

    private static Void l1CacheRetrieve() {
        int id = random.nextInt(NUM_ELEMENTS);
        l1Cache.get(id);
        return null;
    }

    private static Void l2CacheInsert() {
        int id = random.nextInt(NUM_ELEMENTS);
        l2Cache.put(id, "Value" + id);
        return null;
    }

    private static Void l2CacheRetrieve() {
        int id = random.nextInt(NUM_ELEMENTS);
        l2Cache.getIfPresent(id);
        return null;
    }

    private static Void multilevelCacheRetrieve() {
        int id = random.nextInt(NUM_ELEMENTS);
        String value = l1Cache.get(id);
        if (value == null) {
            value = l2Cache.getIfPresent(id);
            if (value == null) {
                try {
                    String sql = "SELECT value FROM test_table WHERE id = ?";
                    PreparedStatement statement = connection.prepareStatement(sql);
                    statement.setInt(1, id);
                    ResultSet resultSet = statement.executeQuery();
                    if (resultSet.next()) {
                        value = resultSet.getString("value");
                        l2Cache.put(id, value);
                        l1Cache.put(id, value);
                    }
                    resultSet.close();
                    statement.close();
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, "Error in multilevel cache retrieve", e);
                }
            } else {
                l1Cache.put(id, value);
            }
        }
        return null;
    }

    private static void analyzeAndLogResults(List<BenchmarkResult> results) {
        LOGGER.info("Performance Analysis:");
        for (BenchmarkResult result : results) {
            logPerformance(result.operation, result.time);
        }

        LOGGER.info("\nPerformance Improvements:");
        BenchmarkResult dbRetrieve = results.stream().filter(r -> r.operation.equals("Database Retrieve")).findFirst()
                .orElseThrow();
        results.stream()
                .filter(r -> !r.operation.equals("Database Retrieve") && !r.operation.equals("Database Insert"))
                .forEach(r -> logImprovement(r.operation + " vs Database Retrieve", dbRetrieve.time, r.time));

        identifyBottlenecks(results);
    }

    private static void logPerformance(String operation, long time) {
        LOGGER.info(String.format("%s: %.2f ms", operation, time / 1_000_000.0));
    }

    private static void logImprovement(String comparison, long baseTime, long improvedTime) {
        double improvement = (baseTime - improvedTime) / (double) baseTime * 100;
        LOGGER.info(String.format("%s: %.2f%% faster", comparison, improvement));
    }

    private static void identifyBottlenecks(List<BenchmarkResult> results) {
        BenchmarkResult slowest = results.stream().max((a, b) -> Long.compare(a.time, b.time)).orElseThrow();
        LOGGER.warning("\nBottleneck: " + slowest.operation + " is the slowest operation.");
    }

    private static class BenchmarkResult {
        String operation;
        long time;

        BenchmarkResult(String operation, long time) {
            this.operation = operation;
            this.time = time;
        }
    }
}
