package com.bank.compliance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stand-in for svc-cus-profile-kyc and the realm's token endpoint on a random
 * local port (JDK HttpServer, no extra dependency), so the integration tests
 * drive the real HTTP adapter and token provider end to end. Serves
 * GET /api/v1/customers/{id} (CustomerResponse shape with the required
 * kycStatus the customer context shows to listed service callers) for the
 * customers registered here, 404 CUSTOMER_NOT_FOUND otherwise, and
 * POST /token for the client-credentials grant.
 */
final class CustomerServiceStub implements AutoCloseable {

    record StubCustomer(String firstName, String lastName, String kycStatus) {
    }

    static final String TOKEN = "stub-service-token-1";

    private final HttpServer server;
    private final Map<String, StubCustomer> customers = new ConcurrentHashMap<>();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger failNextWith = new AtomicInteger(0);
    final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    final AtomicReference<String> lastInteractionId = new AtomicReference<>();
    final AtomicReference<String> lastTokenRequestBody = new AtomicReference<>();

    private CustomerServiceStub(HttpServer server) {
        this.server = server;
    }

    static CustomerServiceStub start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CustomerServiceStub stub = new CustomerServiceStub(server);
        server.createContext("/token", stub::token);
        server.createContext("/api/v1/customers/", stub::customer);
        server.start();
        return stub;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String tokenUri() {
        return baseUrl() + "/token";
    }

    void customer(String id, String firstName, String lastName, String kycStatus) {
        customers.put(id, new StubCustomer(firstName, lastName, kycStatus));
    }

    void clear() {
        customers.clear();
        failNextWith.set(0);
        lastAuthorization.set(null);
        lastInteractionId.set(null);
    }

    /** The next customer request answers with this status, then the stub serves normally again. */
    void failNextWith(int status) {
        failNextWith.set(status);
    }

    int tokenRequests() {
        return tokenRequests.get();
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        lastTokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "{}");
            return;
        }
        respond(exchange, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}");
    }

    private void customer(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastInteractionId.set(exchange.getRequestHeaders().getFirst("x-fapi-interaction-id"));
        int forced = failNextWith.getAndSet(0);
        if (forced != 0) {
            respond(exchange, forced, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"stub failure\"}");
            return;
        }
        String id = exchange.getRequestURI().getPath().substring("/api/v1/customers/".length());
        StubCustomer found = customers.get(id);
        if (found == null) {
            respond(exchange, 404, "{\"code\":\"CUSTOMER_NOT_FOUND\",\"message\":\"Customer not found\",\"interactionId\":\"x\",\"timestamp\":\"2026-10-18T00:00:00Z\"}");
            return;
        }
        respond(exchange, 200, """
            {"customerId":"%s","firstName":"%s","lastName":"%s","email":null,"phoneNumber":null,"creditLimit":1000.0,
             "usedCredit":0.0,"availableCredit":1000.0,"status":"ACTIVE","kycStatus":"%s","creditScore":null,"monthlyIncome":null,
             "createdAt":"2026-10-01T00:00:00Z","lastModifiedAt":"2026-10-01T00:00:00Z"}
            """.formatted(id, found.firstName(), found.lastName(), found.kycStatus()));
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
