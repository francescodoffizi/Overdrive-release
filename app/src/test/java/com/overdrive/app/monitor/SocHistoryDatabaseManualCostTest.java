package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.Test;

public class SocHistoryDatabaseManualCostTest {
    @Test
    public void manualCostUpdateIsAtomicValidatedAndRebuildsTheDailyRollup()
            throws Exception {
        Class.forName("org.h2.Driver");
        SocHistoryDatabase database = new SocHistoryDatabase(null);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:manual-cost-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1", "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE charging_sessions ("
                    + "id BIGINT PRIMARY KEY, start_time BIGINT, end_time BIGINT,"
                    + "energy_added_kwh REAL, electricity_rate REAL, session_cost REAL,"
                    + "tariff_id VARCHAR, tariff_label VARCHAR,"
                    + "post_commit_tariff_applied INTEGER,"
                    + "is_dc INTEGER, peak_power_kw REAL, range_gained_km INTEGER,"
                    + "energy_incomplete INTEGER)");
            statement.execute("CREATE TABLE charging_daily ("
                    + "day_epoch BIGINT PRIMARY KEY, session_count INTEGER,"
                    + "energy_kwh REAL, cost REAL, dc_count INTEGER, ac_count INTEGER,"
                    + "peak_power_kw REAL, soh_at_day REAL, range_gained_km INTEGER,"
                    + "incomplete_count INTEGER)");
            statement.execute("INSERT INTO charging_sessions VALUES "
                    + "(1, 1000, 2000, 10, 0.5, 5, 'home', 'Home', 1,"
                    + "0, 7.2, 30, 0)");

            set(database, "connection", connection);
            set(database, "isInitialized", true);

            assertTrue(database.updateChargingSessionCost(1, 12.5));
            assertSession(statement, 12.5, 1.25, "", "");
            try (ResultSet daily = statement.executeQuery(
                    "SELECT session_count, cost FROM charging_daily WHERE day_epoch = 0")) {
                assertTrue(daily.next());
                assertEquals(1, daily.getInt("session_count"));
                assertEquals(12.5, daily.getDouble("cost"), 0.001);
            }

            assertFalse(database.updateChargingSessionCost(1, Double.NaN));
            assertFalse(database.updateChargingSessionCost(1, Double.MAX_VALUE));
            assertFalse(database.updateChargingSessionCost(1, -2));
            assertSession(statement, 12.5, 1.25, "", "");

            assertTrue(database.updateChargingSessionCost(1, -1));
            assertSession(statement, -1, -1, "", "");
            try (ResultSet daily = statement.executeQuery(
                    "SELECT cost FROM charging_daily WHERE day_epoch = 0")) {
                assertTrue(daily.next());
                assertEquals(0, daily.getDouble("cost"), 0.001);
            }

            statement.execute("UPDATE charging_sessions SET end_time = NULL WHERE id = 1");
            assertFalse(database.updateChargingSessionCost(1, 4));
            assertSession(statement, -1, -1, "", "");
        }
    }

    private static void assertSession(
            Statement statement, double cost, double rate,
            String tariffId, String tariffLabel) throws Exception {
        try (ResultSet row = statement.executeQuery(
                "SELECT session_cost, electricity_rate, tariff_id, tariff_label"
                        + " FROM charging_sessions WHERE id = 1")) {
            assertTrue(row.next());
            assertEquals(cost, row.getDouble("session_cost"), 0.001);
            assertEquals(rate, row.getDouble("electricity_rate"), 0.001);
            assertEquals(tariffId, row.getString("tariff_id"));
            assertEquals(tariffLabel, row.getString("tariff_label"));
        }
    }

    private static void set(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
