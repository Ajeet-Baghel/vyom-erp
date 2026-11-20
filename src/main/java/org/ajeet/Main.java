package org.ajeet;

import org.ajeet.auth.AuthStore;
import org.ajeet.device.DeviceClient;
import org.ajeet.sheets.GoogleSheetsClient;
import org.ajeet.sheets.MemorySheetsClient;
import org.ajeet.sheets.PostgresSheetsClient;
import org.ajeet.sheets.SheetsAuth;
import org.ajeet.sheets.SheetsClient;
import org.ajeet.web.ApiServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {

    /** Folder containing SBXPCDLL64.dll + SBPCCOMM64.dll from the vendor SDK. */
    private static final String DEFAULT_DLL_DIR =
            "vendor-sdk/Z500 sdk/SDK20180628-1/20180622_SDK/SDK/Sample_M50/"
            + "C#_SBXPCDLL_Sample/SBXPCDLLSampleCSharp";

    public static void main(String[] args) throws Exception {
        String spreadsheetId = env("SPREADSHEET_ID", null);
        int port = Integer.parseInt(env("PORT", "8080"));

        String pgUrl = env("PG_URL", null);

        SheetsClient client;
        PostgresSheetsClient pgClient = null;
        if (spreadsheetId != null && !spreadsheetId.isBlank()) {
            try {
                client = new GoogleSheetsClient(SheetsAuth.resolve(), spreadsheetId);
                System.out.println("Connected to Google Sheet: " + spreadsheetId);
            } catch (Exception e) {
                System.err.println("Google Sheets connection failed: " + e.getMessage()
                        + " -> falling back to in-memory mode");
                client = new MemorySheetsClient();
            }
        } else if (pgUrl != null && !pgUrl.isBlank()) {
            try {
                pgClient = new PostgresSheetsClient(pgUrl,
                        env("PG_USER", "postgres"), env("PG_PASSWORD", ""));
                client = pgClient;
                System.out.println("Connected to PostgreSQL: " + pgUrl);
            } catch (Exception e) {
                System.err.println("PostgreSQL connection failed: " + e.getMessage()
                        + " -> falling back to in-memory mode");
                client = new MemorySheetsClient();
            }
        } else {
            System.out.println("No SPREADSHEET_ID/PG_URL set -> in-memory demo mode");
            client = new MemorySheetsClient();
        }

        AuthStore auth = null;
        if (pgClient != null) {
            auth = new AuthStore(pgUrl,
                    env("PG_USER", "postgres"), env("PG_PASSWORD", ""));
            String adminEmail = env("ADMIN_EMAIL", null);
            if (adminEmail != null && !adminEmail.isBlank()) {
                auth.seedAdmin(adminEmail, env("ADMIN_PASSWORD", ""));
                System.out.println("Admin account ready: " + adminEmail);
            } else {
                System.out.println("ADMIN_EMAIL not set -> no admin seeded (set it in .env)");
            }
        }

        DeviceClient device = buildDeviceClient();
        new ApiServer(client, device, auth, pgClient).start(port);
    }

    /** Device sync is enabled only when DEVICE_PASSWORD (comm key) is set. */
    private static DeviceClient buildDeviceClient() {
        String password = env("DEVICE_PASSWORD", null);
        if (password == null || password.isBlank()) {
            System.out.println("DEVICE_PASSWORD not set -> /api/sync disabled");
            return null;
        }
        DeviceClient device = new DeviceClient(
                env("DEVICE_IP", "192.168.31.120"),
                Integer.parseInt(env("DEVICE_PORT", "5005")),
                Integer.parseInt(password),
                Integer.parseInt(env("DEVICE_MACHINE", "1")),
                Path.of(env("DEVICE_DLL_DIR", DEFAULT_DLL_DIR)),
                Path.of("device-sync-state.txt"));
        System.out.println("Device sync enabled: " + device.describe());
        return device;
    }

    /**
     * Resolves a config value: process environment first, then a KEY=VALUE line
     * in a {@code .env} file in the working directory, then {@code def}.
     */
    private static String env(String key, String def) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = dotenv().get(key);
        }
        return (value == null || value.isBlank()) ? def : value;
    }

    private static Map<String, String> dotenvMap;

    private static Map<String, String> dotenv() {
        if (dotenvMap == null) {
            dotenvMap = new HashMap<>();
            try {
                Path envFile = Path.of(".env");
                if (Files.exists(envFile)) {
                    for (String line : Files.readAllLines(envFile)) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        int eq = s.indexOf('=');
                        if (eq > 0) {
                            dotenvMap.put(s.substring(0, eq).trim(),
                                    s.substring(eq + 1).trim());
                        }
                    }
                    System.out.println("Loaded config from .env");
                }
            } catch (IOException e) {
                System.err.println("Failed to read .env: " + e.getMessage());
            }
        }
        return dotenvMap;
    }
}