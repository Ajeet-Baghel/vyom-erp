package org.ajeet.sheets;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-backed {@link SheetsClient}. Punches are stored in a typed
 * {@code punches} table and device users in {@code users}; {@link #readAll()}
 * returns a left join so names appear in the UI once the device user list has
 * been synced. The UNIQUE constraint on (enroll_number, punch_time,
 * verify_label) makes inserts idempotent — re-running a sync never duplicates
 * a punch even if the timestamp watermark lets it through.
 */
public class PostgresSheetsClient implements SheetsClient {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Connection conn;
    private final String url;

    public PostgresSheetsClient(String url, String user, String password) throws IOException {
        this.url = url;
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            enroll_number INT PRIMARY KEY,
                            name          TEXT NOT NULL DEFAULT ''
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS punches (
                            id            BIGSERIAL PRIMARY KEY,
                            enroll_number INT NOT NULL,
                            punch_time    TIMESTAMP NOT NULL,
                            verify_label  TEXT NOT NULL,
                            UNIQUE (enroll_number, punch_time, verify_label)
                        )""");
            }
        } catch (SQLException e) {
            throw new IOException("PostgreSQL init failed: " + e.getMessage(), e);
        }
    }

    /** Upserts device users; names appear in readAll() output via the join. */
    public synchronized void upsertUsers(Map<Integer, String> users) throws IOException {
        if (users.isEmpty()) return;
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO users (enroll_number, name) VALUES (?, ?)
                ON CONFLICT (enroll_number)
                DO UPDATE SET name = EXCLUDED.name
                WHERE users.name <> EXCLUDED.name""")) {
            for (Map.Entry<Integer, String> e : users.entrySet()) {
                ps.setInt(1, e.getKey());
                ps.setString(2, e.getValue() == null ? "" : e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IOException("user upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<List<Object>> readAll() throws IOException {
        List<List<Object>> out = new ArrayList<>();
        out.add(List.of("Employee ID", "Name", "Punch Time", "Verify Mode"));
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.enroll_number, COALESCE(u.name, ''),
                       to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
                FROM punches p
                LEFT JOIN users u ON u.enroll_number = p.enroll_number
                ORDER BY p.punch_time, p.id""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(List.of(
                        rs.getInt(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)));
            }
        } catch (SQLException e) {
            throw new IOException("readAll failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Row contract: [enrollNumber, "yyyy-MM-dd HH:mm:ss", verifyLabel]. */
    @Override
    public synchronized void appendRow(List<Object> values) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO punches (enroll_number, punch_time, verify_label)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING""")) {
            ps.setInt(1, Integer.parseInt(String.valueOf(values.get(0))));
            ps.setObject(2, LocalDateTime.parse(String.valueOf(values.get(1)), FMT));
            ps.setString(3, String.valueOf(values.get(2)));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("appendRow failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void updateRow(int rowNumber, List<Object> values) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE punches SET enroll_number = ?, punch_time = ?, verify_label = ?
                WHERE id = (SELECT id FROM punches ORDER BY punch_time, id
                            LIMIT 1 OFFSET ?)""")) {
            ps.setInt(1, Integer.parseInt(String.valueOf(values.get(0))));
            ps.setObject(2, LocalDateTime.parse(String.valueOf(values.get(1)), FMT));
            ps.setString(3, String.valueOf(values.get(2)));
            ps.setInt(4, rowNumber - 2); // row 1 is the header
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("updateRow failed: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void deleteRow(int rowNumber) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM punches
                WHERE id = (SELECT id FROM punches ORDER BY punch_time, id
                            LIMIT 1 OFFSET ?)""")) {
            ps.setInt(1, rowNumber - 2);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("deleteRow failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------- attendance API

    /** One punch row for the portal: name resolved via the users join. */
    public record AttendancePunch(int enrollNumber, String name, String time, String verifyLabel) {}

    /** Punches for a single device user, newest first (the employee's own view). */
    public synchronized List<AttendancePunch> punchesFor(int enrollNumber) throws IOException {
        return punchRows("WHERE p.enroll_number = ?", enrollNumber);
    }

    /** Everyone who punched today - the admin dashboard card. */
    public synchronized List<AttendancePunch> punchesToday() throws IOException {
        return punchRows("WHERE p.punch_time::date = current_date", null);
    }

    /** All punches, newest first - the admin full log (capped). */
    public synchronized List<AttendancePunch> punchesAll() throws IOException {
        return punchRows("", null);
    }

    /** Device users not yet claimed by any web account - the approve dropdown. */
    public synchronized Map<Integer, String> unlinkedUsers() throws IOException {
        Map<Integer, String> out = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT u.enroll_number, u.name FROM users u
                     LEFT JOIN accounts a ON a.enroll_number = u.enroll_number
                     WHERE a.id IS NULL ORDER BY u.enroll_number""")) {
            while (rs.next()) out.put(rs.getInt(1), rs.getString(2));
        } catch (SQLException e) {
            throw new IOException("unlinked users failed: " + e.getMessage(), e);
        }
        return out;
    }

    private List<AttendancePunch> punchRows(String where, Integer enroll) throws IOException {
        List<AttendancePunch> out = new ArrayList<>();
        String sql = """
                SELECT p.enroll_number, COALESCE(u.name,''),
                       to_char(p.punch_time, 'YYYY-MM-DD HH24:MI:SS'), p.verify_label
                FROM punches p LEFT JOIN users u ON u.enroll_number = p.enroll_number
                """ + where + " ORDER BY p.punch_time DESC LIMIT 500";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (enroll != null) ps.setInt(1, enroll);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AttendancePunch(rs.getInt(1), rs.getString(2),
                            rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new IOException("attendance query failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public String describe() {
        return "PostgreSQL (" + url + ")";
    }
}
