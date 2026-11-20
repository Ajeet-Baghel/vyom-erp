package org.ajeet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ajeet.auth.AuthStore;
import org.ajeet.device.DeviceClient;
import org.ajeet.sheets.PostgresSheetsClient;
import org.ajeet.sheets.SheetsClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Minimal REST API over the sheet store:
 *   GET    /api/status        -> { mode }
 *   GET    /api/rows          -> { values: [[...], ...] }  (row 1 = header)
 *   POST   /api/rows          -> body { "values": [...] }
 *   PUT    /api/rows/{n}      -> body { "values": [...] }  (n = sheet row, >= 2)
 *   DELETE /api/rows/{n}
 *   POST   /api/sync          -> body { "since": "ISO" }?  pull device log, append new punches
 */
public class ApiServer {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SheetsClient client;
    private final DeviceClient device;
    private final AuthStore auth;
    private final PostgresSheetsClient pg;

    public ApiServer(SheetsClient client) {
        this(client, null, null, null);
    }

    public ApiServer(SheetsClient client, DeviceClient device) {
        this(client, device, null, null);
    }

    /** Full-featured server: auth + attendance endpoints need Postgres mode. */
    public ApiServer(SheetsClient client, DeviceClient device,
                     AuthStore auth, PostgresSheetsClient pg) {
        this.client = client;
        this.device = device;
        this.auth = auth;
        this.pg = pg;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", this::route);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.printf("API listening on http://localhost:%d (%s)%n", port, client.describe());
    }

    private void route(HttpExchange ex) throws IOException {
        try {
            addCors(ex);
            if ("OPTIONS".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring("/api".length());
            if (path.isEmpty()) path = "/";

            if (method.equals("GET") && path.equals("/status")) {
                ObjectNode body = mapper.createObjectNode();
                body.put("mode", client.describe());
                send(ex, 200, body);
            } else if (method.equals("POST") && path.equals("/sync")) {
                if (device == null) {
                    send(ex, 503, mapper.createObjectNode()
                            .put("error", "device sync not configured (set DEVICE_IP/DEVICE_PASSWORD)"));
                } else {
                    DeviceClient.SyncResult result = device.sync(client, readSince(ex));
                    ObjectNode body = mapper.createObjectNode();
                    body.put("appended", result.appended());
                    body.put("watermark", result.watermark().toString());
                    body.put("newest", result.newest().toString());
                    send(ex, 200, body);
                }
            } else if (method.equals("POST") && path.equals("/auth/register")) {
                requireAuth();
                JsonNode body = readJson(ex);
                long id = auth.register(body.path("email").asText(""),
                        body.path("password").asText(""));
                send(ex, 201, mapper.createObjectNode()
                        .put("ok", true).put("accountId", id).put("status", "pending"));
            } else if (method.equals("POST") && path.equals("/auth/login")) {
                requireAuth();
                JsonNode body = readJson(ex);
                AuthStore.Account acct = auth.verify(body.path("email").asText(""),
                        body.path("password").asText(""));
                ObjectNode out = accountJson(acct);
                out.put("token", auth.newSession(acct.id()));
                send(ex, 200, out);
            } else if (method.equals("POST") && path.equals("/auth/logout")) {
                if (auth != null) auth.logout(bearer(ex));
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/auth/me")) {
                send(ex, 200, accountJson(requireAccount(ex)));
            } else if (method.equals("GET") && path.equals("/attendance/my")) {
                AuthStore.Account acct = requireAccount(ex);
                if (!"active".equals(acct.status()) || acct.enrollNumber() == null)
                    throw new ForbiddenException("account not yet approved by an admin");
                send(ex, 200, punchesJson(requireStore().punchesFor(acct.enrollNumber())));
            } else if (method.equals("GET") && path.equals("/attendance/today")) {
                requireAdmin(ex);
                send(ex, 200, punchesJson(requireStore().punchesToday()));
            } else if (method.equals("GET") && path.equals("/attendance/all")) {
                requireAdmin(ex);
                send(ex, 200, punchesJson(requireStore().punchesAll()));
            } else if (method.equals("GET") && path.equals("/admin/pending")) {
                requireAdmin(ex);
                ObjectNode out = mapper.createObjectNode();
                out.set("accounts", mapper.valueToTree(auth.pendingAccounts()));
                out.set("deviceUsers", mapper.valueToTree(requireStore().unlinkedUsers()));
                send(ex, 200, out);
            } else if (method.equals("GET") && path.equals("/admin/accounts")) {
                requireAdmin(ex);
                ObjectNode out = mapper.createObjectNode();
                out.set("accounts", mapper.valueToTree(auth.allAccounts()));
                send(ex, 200, out);
            } else if (method.equals("POST") && path.equals("/admin/approve")) {
                requireAdmin(ex);
                JsonNode body = readJson(ex);
                auth.approve(body.path("accountId").asLong(), body.path("enrollNumber").asInt());
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("POST") && path.equals("/admin/reject")) {
                requireAdmin(ex);
                JsonNode body = readJson(ex);
                auth.reject(body.path("accountId").asLong());
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else if (method.equals("GET") && path.equals("/rows")) {
                ObjectNode body = mapper.createObjectNode();
                body.set("values", mapper.valueToTree(client.readAll()));
                send(ex, 200, body);
            } else if (method.equals("POST") && path.equals("/rows")) {
                client.appendRow(readValues(ex));
                send(ex, 201, mapper.createObjectNode().put("ok", true));
            } else if ((method.equals("PUT") || method.equals("DELETE"))
                    && path.startsWith("/rows/")) {
                int rowNumber = Integer.parseInt(path.substring("/rows/".length()));
                if (method.equals("PUT")) {
                    client.updateRow(rowNumber, readValues(ex));
                } else {
                    client.deleteRow(rowNumber);
                }
                send(ex, 200, mapper.createObjectNode().put("ok", true));
            } else {
                send(ex, 404, mapper.createObjectNode().put("error", "not found"));
            }
        } catch (AuthStore.AuthException e) {
            send(ex, 401, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (ForbiddenException e) {
            send(ex, 403, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, mapper.createObjectNode().put("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, mapper.createObjectNode().put("error", e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private List<Object> readValues(HttpExchange ex) throws IOException {
        List<Object> values = new ArrayList<>();
        try (InputStream in = ex.getRequestBody()) {
            JsonNode node = mapper.readTree(in.readAllBytes());
            for (JsonNode v : node.withArray("values")) {
                values.add(v.isValueNode() ? mapper.treeToValue(v, Object.class) : v.toString());
            }
        }
        if (values.isEmpty()) throw new IllegalArgumentException("Body must be {\"values\": [ ... ]}");
        return values;
    }

    /** Optional {"since": "ISO-local-datetime"} from the request body, else null. */
    private LocalDateTime readSince(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readAllBytes();
        }
        if (raw.length == 0) return null;
        JsonNode node = mapper.readTree(raw);
        JsonNode since = node.get("since");
        return (since != null && since.isTextual() && !since.asText().isBlank())
                ? LocalDateTime.parse(since.asText().trim())
                : null;
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    /** Guard errors distinct from 400 validation: missing/expired/forbidden auth. */
    private static class ForbiddenException extends IOException {
        ForbiddenException(String msg) { super(msg); }
    }

    private String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return (h != null && h.startsWith("Bearer ")) ? h.substring(7).trim() : null;
    }

    private void requireAuth() throws IOException {
        if (auth == null) throw new ForbiddenException("auth needs PostgreSQL mode (PG_URL)");
    }

    /** Bearer token -> account, or throws 403. */
    private AuthStore.Account requireAccount(HttpExchange ex) throws IOException {
        requireAuth();
        AuthStore.Account acct = auth.resolve(bearer(ex));
        if (acct == null) throw new ForbiddenException("invalid or expired session");
        return acct;
    }

    private void requireAdmin(HttpExchange ex) throws IOException {
        if (!"admin".equals(requireAccount(ex).role()))
            throw new ForbiddenException("admin only");
    }

    private PostgresSheetsClient requireStore() throws IOException {
        if (pg == null) throw new ForbiddenException("attendance requires PostgreSQL mode");
        return pg;
    }

    private JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] raw = in.readAllBytes();
            return raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        }
    }

    private ObjectNode accountJson(AuthStore.Account a) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", a.id());
        out.put("email", a.email());
        out.put("role", a.role());
        out.put("status", a.status());
        out.put("name", a.name());
        if (a.enrollNumber() != null) out.put("enrollNumber", a.enrollNumber());
        return out;
    }

    private JsonNode punchesJson(List<PostgresSheetsClient.AttendancePunch> punches) {
        ObjectNode out = mapper.createObjectNode();
        out.set("punches", mapper.valueToTree(punches));
        return out;
    }

    private void send(HttpExchange ex, int status, JsonNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
