package com.example;

import java.sql.Connection;
import java.util.Map;
import com.google.common.cache.Cache;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.cache.CacheBuilder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Hello world!
 *
 */
public class AdvDBCachingBenchmark {
    private static final String DB_URL = "jdbc:mysql://localhost:3306/testdb";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "Root";
    private static final int NUM_ELEMENTS = 100000;
    private static final int L1_CACHE_SIZE = 10000;
    private static final int L2_CACHE_SIZE = 100000;
    private static final int L3_CACHE_SIZE = 100000;
    private static final int L2_CACHE_DURATTION_MINUTES = 10;

    private static Connection connection;
    private static Map<Integer, String> l1Cache;
    private static Cache<Integer, String> l2Cache;

    public static void main(String[] args) {
        try {
            setupDatabase();
            setupCaches();
            double dbInsertTime = benchmarkDatabaseInsert();
            double dbRetrieveTime = benchMarkDatabaseRetrieve();
            double l1CacheInsertTime = benchMarkL1CacheInsert();
            double l1CacheRetrieveTime = benchMarkL1CacheRetrieve();
            double l2CacheInsertTime = benchMarkL2CacheInsert();
            double l2CacheRetrieveTime = benchMarkL2CacheRetrieve();
            double multilevelCacheRetrieveTime = benchmarkMultilevelCacheRetrieve();
            printResults(dbInsertTime, dbRetrieveTime, l1CacheInsertTime, l1CacheRetrieveTime, l2CacheInsertTime,
                    l2CacheRetrieveTime, multilevelCacheRetrieveTime);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private static void setupDatabase() {
        try {
            connection = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
            Statement statement = connection.createStatement();
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, value VARCHAR(255))");

            statement.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void setupCaches() {
        l1Cache = new LinkedHashMap<Integer, String>(L1_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                return size() > L1_CACHE_SIZE;
            }
        };
        l2Cache = CacheBuilder.newBuilder()
                .maximumSize(L2_CACHE_SIZE)
                .expireAfterAccess(L2_CACHE_DURATTION_MINUTES, TimeUnit.MINUTES)
                .build();
    }

    private static double benchmarkDatabaseInsert() throws SQLException {
        long startTime = System.nanoTime();
        String sql = "INSERT INTO test_table (id, value) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE value = VALUES(value)";
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            statement.setInt(1, i);
            statement.setString(2, "Value" + i);
            statement.addBatch();
            if (i % 100 == 0) {
                statement.executeBatch();
            }
        }
        statement.executeBatch();
        statement.close();
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchMarkDatabaseRetrieve() throws SQLException {
        double startTime = System.nanoTime();
        String sql = "SELECT * FROM test_table WHERE id = ?";
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            statement.setInt(1, i);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                resultSet.getString("value");
            }
            resultSet.close();

        }
        statement.close();
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchMarkL1CacheInsert() {
        double startTime = System.nanoTime();
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            l1Cache.put(i, "Value" + i);
        }
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchMarkL1CacheRetrieve() {
        double startTime = System.nanoTime();
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            l1Cache.get(i);
        }
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchMarkL2CacheInsert() {
        double startTime = System.nanoTime();
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            l2Cache.put(i, "Value" + i);
        }
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchMarkL2CacheRetrieve() {
        double startTime = System.nanoTime();
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            l2Cache.getIfPresent(i);
        }
        double endTime = System.nanoTime();
        return (endTime - startTime) / 1_000_000_000;
    }

    private static double benchmarkMultilevelCacheRetrieve() throws SQLException {
        double startTime = System.nanoTime();
        int l1Hits = 0;
        int l2Hits = 0;
        String sql = "SELECT * FROM test_table WHERE id = ?";
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < NUM_ELEMENTS; i++) {
            String value = l1Cache.get(i);
            if (value == null) {
                value = l2Cache.getIfPresent(i);
                if (value == null) {
                    // String sql = "SELECT * FROM test_table WHERE id = ?";
                    // PreparedStatement statement = connection.prepareStatement(sql);
                    statement.setInt(1, i);
                    ResultSet resultSet = statement.executeQuery();
                    if (resultSet.next()) {
                        value = resultSet.getString("value");
                        l2Cache.put(i, value);
                        l1Cache.put(i, value);
                    }
                    resultSet.close();
                    // statement.close();

                } else {
                    l1Cache.put(i, value);
                    l2Hits++;
                }
            } else {
                l1Hits++;
            }
        }
        statement.close();
        double endTime = System.nanoTime();
        System.out.println("L1 Cache Hits: " + l1Hits);
        System.out.println("L2 Cache Hits: " + l2Hits);
        return (endTime - startTime) / 1_000_000_000;
    }
    // private static double benchmarkMultilevelCacheRetrieve() throws SQLException,
    // InterruptedException {
    // ExecutorService executorService = Executors.newFixedThreadPool(4);
    // double startTime = System.nanoTime();
    // AtomicInteger l1Hits = new AtomicInteger(0);
    // AtomicInteger l2Hits = new AtomicInteger(0);

    // // Prepare statement outside the loop
    // String sql = "SELECT * FROM test_table WHERE id IN (%s)";

    // // Step 1: Track missing IDs
    // List<Integer> missingIds = new ArrayList<>();

    // for (int i = 0; i < NUM_ELEMENTS; i++) {
    // final int x = i;
    // executorService.execute(() -> {
    // String value = l1Cache.get(x);
    // if (value == null) {
    // value = l2Cache.getIfPresent(x);
    // if (value == null) {
    // missingIds.add(x); // Cache miss, track the ID
    // } else {
    // l1Cache.put(x, value); // Found in L2, put it in L1
    // l2Hits.incrementAndGet();
    // }
    // } else {
    // l1Hits.incrementAndGet();
    // }

    // // Step 2: Query DB in batches when missingIds list reaches a certain size
    // if (missingIds.size() >= 100 || x == NUM_ELEMENTS - 1) {
    // if (!missingIds.isEmpty()) {
    // // Prepare the IN clause for batch querying
    // String query = String.format(sql, missingIds.stream()
    // .map(String::valueOf)
    // .collect(Collectors.joining(",")));

    // try {
    // PreparedStatement batchStatement = connection.prepareStatement(query);
    // ResultSet resultSet = batchStatement.executeQuery();

    // // Step 3: Process and cache the results in bulk
    // while (resultSet.next()) {
    // int id = resultSet.getInt("id");
    // String resultValue = resultSet.getString("value");

    // l2Cache.put(id, resultValue); // Populate L2
    // l1Cache.put(id, resultValue); // Populate L1

    // }

    // resultSet.close();
    // batchStatement.close();
    // } catch (SQLException e) {
    // e.printStackTrace();
    // }
    // }

    // // Clear the missing IDs list for the next batch
    // missingIds.clear();
    // }

    // });
    // }
    // executorService.shutdown();
    // executorService.awaitTermination(1, TimeUnit.MINUTES);

    // double endTime = System.nanoTime();
    // System.out.println("L1 Cache Hits: "+l1Hits);System.out.println("L2 Cache
    // Hits: "+l2Hits);return(endTime-startTime)/1_000_000_000;
    // }

    private static void printResults(double dbInsertTime, double dbRetrieveTime,
            double l1CacheInsertTime, double l1CacheRetrieveTime, double l2CacheInsertTime,
            double l2CacheRetrieveTime, double multilevelCacheRetrieveTime) {
        System.out.println("Database Insert Time: " + dbInsertTime + " seconds");
        System.out.println("Database Retrieve Time: " + dbRetrieveTime + " seconds");
        System.out.println("L1 Cache Insert Time: " + l1CacheInsertTime + " seconds");
        System.out.println("L1 Cache Retrieve Time: " + l1CacheRetrieveTime + " seconds");
        System.out.println("L2 Cache Insert Time: " + l2CacheInsertTime + " seconds");
        System.out.println("L2 Cache Retrieve Time: " + l2CacheRetrieveTime + " seconds");
        System.out.println("Multilevel Cache Retrieve Time: " + multilevelCacheRetrieveTime + " seconds");
    }
}

