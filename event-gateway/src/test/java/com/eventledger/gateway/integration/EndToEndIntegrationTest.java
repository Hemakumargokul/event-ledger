package com.eventledger.gateway.integration;

import com.eventledger.account.AccountApplication;
import com.eventledger.gateway.GatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §10: both real service contexts in one JVM on random ports, talking
 * over real HTTP. Proves the end-to-end behaviors that the per-service suites
 * can only prove in isolation: out-of-order tolerance, proxied balance math,
 * duplicate replay across services, and one shared trace ID in both
 * services' logs.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndIntegrationTest {

    private static ConfigurableApplicationContext accountContext;
    private static ConfigurableApplicationContext gatewayContext;
    private static String gatewayUrl;

    private static final TestRestTemplate REST = new TestRestTemplate();
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void startBothServices() {
        // Both application.yml files are on this classpath and the Gateway's
        // wins for both contexts. Overrides go in as command-line args:
        // builder.properties() only sets *default* properties, which any
        // application.yml value silently overrides.
        accountContext = new SpringApplicationBuilder(AccountApplication.class)
                .run("--server.port=0",
                        "--spring.application.name=account-service",
                        "--logging.structured.ecs.service.name=account-service");
        int accountPort = Integer.parseInt(
                accountContext.getEnvironment().getProperty("local.server.port"));

        gatewayContext = new SpringApplicationBuilder(GatewayApplication.class)
                .run("--server.port=0",
                        "--account-service.base-url=http://localhost:" + accountPort);
        gatewayUrl = "http://localhost:"
                + gatewayContext.getEnvironment().getProperty("local.server.port");
    }

    @AfterAll
    static void stopBothServices() {
        if (gatewayContext != null) {
            gatewayContext.close();
        }
        if (accountContext != null) {
            accountContext.close();
        }
    }

    private static ResponseEntity<String> submit(String eventId, String type, String amount,
                                                 String timestamp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return REST.postForEntity(gatewayUrl + "/events", new HttpEntity<>("""
                {
                  "eventId": "%s",
                  "accountId": "acct-e2e",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(eventId, type, amount, timestamp), headers), String.class);
    }

    @Test
    @Order(1)
    void outOfOrderSubmissionsListChronologicallyAndBalanceIsCreditsMinusDebits() {
        // arrival order deliberately != chronological order
        assertThat(submit("evt-e2e-2", "DEBIT", "40.00", "2026-05-15T14:02:00Z").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(submit("evt-e2e-1", "CREDIT", "100.00", "2026-05-15T14:01:00Z").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(submit("evt-e2e-3", "CREDIT", "10.00", "2026-05-15T14:03:00Z").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode listing = JSON.readTree(
                REST.getForEntity(gatewayUrl + "/events?account=acct-e2e", String.class).getBody());
        assertThat(listing.get("events")).hasSize(3);
        assertThat(listing.get("events").get(0).get("eventId").asString()).isEqualTo("evt-e2e-1");
        assertThat(listing.get("events").get(1).get("eventId").asString()).isEqualTo("evt-e2e-2");
        assertThat(listing.get("events").get(2).get("eventId").asString()).isEqualTo("evt-e2e-3");

        JsonNode balance = JSON.readTree(
                REST.getForEntity(gatewayUrl + "/accounts/acct-e2e/balance", String.class).getBody());
        assertThat(balance.get("balance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("70.00")); // 100 - 40 + 10
        assertThat(balance.get("currency").asString()).isEqualTo("USD");
    }

    @Test
    @Order(2)
    void duplicateReplayReturns200AndLeavesTheBalanceUnchanged() {
        ResponseEntity<String> replay =
                submit("evt-e2e-1", "CREDIT", "100.00", "2026-05-15T14:01:00Z");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode balance = JSON.readTree(
                REST.getForEntity(gatewayUrl + "/accounts/acct-e2e/balance", String.class).getBody());
        assertThat(balance.get("balance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    @Order(3)
    void bothServicesLogTheSameTraceIdForOneRequest(CapturedOutput output) {
        assertThat(submit("evt-e2e-trace", "CREDIT", "5.00", "2026-05-15T14:04:00Z")
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String gatewayTrace = traceIdOfLogLine(output, "Accepted event evt-e2e-trace");
        String accountTrace = traceIdOfLogLine(output, "Applied transaction evt-e2e-trace");

        assertThat(gatewayTrace).isNotEmpty();
        assertThat(gatewayTrace).isEqualTo(accountTrace);
    }

    /** Finds the ECS JSON log line containing the marker and extracts its trace id. */
    private static String traceIdOfLogLine(CapturedOutput output, String marker) {
        String line = output.getOut().lines()
                .filter(l -> l.contains(marker))
                .reduce((first, second) -> second) // last occurrence
                .orElseThrow(() -> new AssertionError("no log line containing: " + marker));
        Matcher matcher = Pattern.compile("\"traceId\"\\s*:\\s*\"([0-9a-f]{32})\"").matcher(line);
        assertThat(matcher.find())
                .as("log line should carry a trace id: %s", line)
                .isTrue();
        return matcher.group(1);
    }
}
