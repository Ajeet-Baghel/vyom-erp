package org.ajeet.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Web-login accounts + sessions on the same Postgres DB as punches.
 *
 * accounts.enroll_number links a login to the device identity in `users`;
 * registrations start "pending" until an admin approves and links an enroll
 * number. Sessions are opaque bearer tokens (Authorization header).
 * Passwords are hashed with PBKDF2 - JDK built-in, no external deps.
 */
public class AuthStore {

    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_BITS = 256;

    private final Connection conn;
    private final SecureRandom random = new SecureRandom();

    public AuthStore(String url, String user, String password) throws IOException {
        try {
            this.conn = DriverManager.getConnection(url, user, password);
            try (Statement st = conn.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS accounts (
                            id            BIGSERIAL PRIMARY KEY,
                            email         TEXT NOT NULL UNIQUE,
                            password_hash TEXT NOT NULL,
                            role          TEXT NOT NULL DEFAULT 'employee',
                            enroll_number INT,
                            status        TEXT NOT NULL DEFAULT 'pending',
                            created_at    TIMESTAMP NOT NULL DEFAULT now()
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sessions (
                            token      TEXT PRIMARY KEY,
                            account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                            created_at TIMESTAMP NOT NULL DEFAULT now()
                        )""");
            }
        } catch (SQLException e) {
            throw new IOException("AuthStore init failed: " + e.getMessage(), e);
        }
    }

    /** Account view safe to serialize to the client (no password hash). */
    public record Account(long id, String email, String role,
                          Integer enrollNumber, String name, String status) {}

    /** Bad-credentials / validation errors - surfaced to the client as 4xx. */
    public static class AuthException extends IOException {
        public AuthException(String message) { super(message); }
    }

    // ---------------------------------------------------------------- signup

    /** Creates a pending account. Throws AuthException on invalid/taken email. */
    public synchronized long register(String email, String password) throws IOException {
        email = normalize(email);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new AuthException("invalid email address");
        if (password == null || password.length() < 6)
            throw new AuthException("password must be at least 6 characters");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (email, password_hash) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, hashPassword(password));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState()))
                throw new AuthException("an account with that email already exists");
            throw new IOException("register failed: " + e.getMessage(), e);
        }
    }

    /** Checks credentials; returns the account (any status) or throws AuthException. */
    public synchronized Account verify(String email, String password) throws IOException {
        AccountRow row = findByEmail(normalize(email));
        if (row == null || !verifyPassword(password, row.passwordHash()))
            throw new AuthException("invalid email or password");
        return row.toAccount();
    }

    // ---------------------------------------------------------------- session

    /** Creates a bearer token for a verified account. */
    public synchronized String newSession(long accountId) throws IOException {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO sessions (token, account_id) VALUES (?, ?)")) {
            ps.setString(1, token);
            ps.setLong(2, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("session create failed: " + e.getMessage(), e);
        }
        return token;
    }

    /** Resolves a bearer token to its account, or null if unknown. */
    public synchronized Account resolve(String token) throws IOException {
        if (token == null || token.isBlank()) return null;
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, a.email, a.role, a.enroll_number,
                       COALESCE(u.name, ''), a.status
                FROM sessions s
                JOIN accounts a ON a.id = s.account_id
                LEFT JOIN users u ON u.enroll_number = a.enroll_number
                WHERE s.token = ?""")) {
            ps.setString(1, token);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readAccount(rs) : null;
            }
        } catch (SQLException e) {
            throw new IOException("resolve failed: " + e.getMessage(), e);
        }
    }

    public synchronized void logout(String token) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("logout failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ admin

    /** Sign-ups waiting for an admin to link them to a device user. */
    public synchronized List<Account> pendingAccounts() throws IOException {
        return accountsWhere("a.status = 'pending'");
    }

    /** Every account, for the admin roster. */
    public synchronized List<Account> allAccounts() throws IOException {
        return accountsWhere("true");
    }

    /**
     * Links a pending account to a device enroll number and activates it.
     * Throws AuthException if that enroll number is already claimed.
     */
    public synchronized void approve(long accountId, int enrollNumber) throws IOException {
        try (PreparedStatement check = conn.prepareStatement(
                "SELECT COUNT(*) FROM accounts WHERE enroll_number = ? AND id <> ?")) {
            check.setInt(1, enrollNumber);
            check.setLong(2, accountId);
            try (ResultSet rs = check.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0)
                    throw new AuthException("that device user is already linked to another account");
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE accounts SET enroll_number = ?, status = 'active'
                    WHERE id = ?""")) {
                ps.setInt(1, enrollNumber);
                ps.setLong(2, accountId);
                if (ps.executeUpdate() == 0) throw new AuthException("account not found");
            }
        } catch (SQLException e) {
            throw new IOException("approve failed: " + e.getMessage(), e);
        }
    }

    public synchronized void reject(long accountId) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET status = 'rejected' WHERE id = ?")) {
            ps.setLong(1, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IOException("reject failed: " + e.getMessage(), e);
        }
    }

    /** Creates the first admin account once (from .env). No-op if an admin exists. */
    public synchronized void seedAdmin(String email, String password) throws IOException {
        email = normalize(email);
        if (email.isEmpty() || password == null || password.isBlank()) return;
        try {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COUNT(*) FROM accounts WHERE role = 'admin'")) {
                rs.next();
                if (rs.getInt(1) > 0) return;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO accounts (email, password_hash, role, status)
                    VALUES (?, ?, 'admin', 'active')""")) {
                ps.setString(1, email);
                ps.setString(2, hashPassword(password));
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IOException("seedAdmin failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private record AccountRow(long id, String email, String role, Integer enrollNumber,
                              String name, String status, String passwordHash) {
        Account toAccount() { return new Account(id, email, role, enrollNumber, name, status); }
    }

    private AccountRow findByEmail(String email) throws IOException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.id, a.email, a.role, a.enroll_number,
                       COALESCE(u.name, ''), a.status, a.password_hash
                FROM accounts a LEFT JOIN users u ON u.enroll_number = a.enroll_number
                WHERE a.email = ?""")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new AccountRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        (Integer) rs.getObject(4), rs.getString(5), rs.getString(6), rs.getString(7));
            }
        } catch (SQLException e) {
            throw new IOException("lookup failed: " + e.getMessage(), e);
        }
    }

    private List<Account> accountsWhere(String where) throws IOException {
        List<Account> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT a.id, a.email, a.role, a.enroll_number,
                            COALESCE(u.name, ''), a.status
                     FROM accounts a LEFT JOIN users u ON u.enroll_number = a.enroll_number
                     """ + " WHERE " + where + " ORDER BY a.id")) {
            while (rs.next()) out.add(readAccount(rs));
        } catch (SQLException e) {
            throw new IOException("accounts query failed: " + e.getMessage(), e);
        }
        return out;
    }

    private static Account readAccount(ResultSet rs) throws SQLException {
        return new Account(rs.getLong(1), rs.getString(2), rs.getString(3),
                (Integer) rs.getObject(4), rs.getString(5), rs.getString(6));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    // ------------------------------------------------------- PBKDF2 hashing

    /** Stored as "iterations:saltB64:hashB64". */
    private String hashPassword(String password) throws IOException {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt, PBKDF2_ITERATIONS);
        Base64.Encoder b64 = Base64.getEncoder();
        return PBKDF2_ITERATIONS + ":" + b64.encodeToString(salt) + ":" + b64.encodeToString(hash);
    }

    private boolean verifyPassword(String password, String stored) throws IOException {
        try {
            String[] parts = stored.split(":");
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws IOException {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IOException("password hash failed", e);
        }
    }
}