package org.ajeet.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.ajeet.device.DeviceClient;
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

    public ApiServer(SheetsClient client) {
        this(client, null);
    }

    public ApiServer(SheetsClient client, DeviceClient device) {
        this.client = client;
        this.device = device;
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
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
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
