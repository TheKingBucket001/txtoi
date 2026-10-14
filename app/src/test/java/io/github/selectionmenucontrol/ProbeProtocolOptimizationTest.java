package io.github.selectionmenucontrol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class ProbeProtocolOptimizationTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";
    private static final String BUILD = "a".repeat(64);
    private static String record(int boot, int version, long uptime) {
        return ProbeProtocol.requestPrefix(NONCE, boot, version, BUILD) + uptime;
    }

    @Test public void parserRejectsMalformedAndOversizedRequests() {
        for (String value : Arrays.asList(null, "", "v1:" + NONCE + ":1:18:10",
                "v2:pending:0:0:0", record(-1, 18, 10), record(1, 0, 10), record(1, 18, -1),
                "v3:" + NONCE + ":2147483648:18:1:" + BUILD + ":10",
                "v3:" + NONCE + ":1:18:1:" + BUILD + ":9223372036854775808",
                record(1, 18, 10) + ":extra", record(1, 18, 10).replace(BUILD, "bad"),
                record(1, 18, 10).replace(":18:1:", ":18:0:"), "x".repeat(241))) {
            assertNull(value, ProbeProtocol.parse(value));
        }
        assertNotNull(ProbeProtocol.parse(record(0, 18, 0)));
    }

    @Test public void exactValidityBoundaryIsAcceptedButExpiredOrFutureRecordsAreNot() {
        ProbeProtocol.Record record = ProbeProtocol.parse(record(1, 18, 100));
        assertTrue(ProbeProtocol.currentRequest(record, 1, 100, 5000));
        assertTrue(ProbeProtocol.currentRequest(record, 1, 5100, 5000));
        assertFalse(ProbeProtocol.currentRequest(record, 1, 5101, 5000));
        assertFalse(ProbeProtocol.currentRequest(record, 1, 99, 5000));
    }

    @Test public void responseMustMatchCurrentBootVersionNonceAndRequestTimestamp() {
        String response = record(1, 18, 120);
        assertEquals(ProbeProtocol.Status.READY, evaluate(response, 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.NO_RESPONSE, evaluate(response, 121, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.NO_RESPONSE, ProbeProtocol.evaluate(response,
                "ffffffffffffffffffffffffffffffff", 100, 1, 18, BUILD, 130, 5000));
        assertEquals(ProbeProtocol.Status.NO_RESPONSE, evaluate(response, 100, 2, 18, 130));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE, evaluate(response, 100, 1, 17, 130));
        assertEquals(ProbeProtocol.Status.READ_FAILED, evaluate(response, 100, -1, 18, 130));
        assertEquals(ProbeProtocol.Status.NO_RESPONSE, evaluate(null, 100, 1, 18, 130));
    }

    @Test public void extremeParsedTimestampCannotOverflowTheFreshnessCheck() {
        ProbeProtocol.Record response = ProbeProtocol.parse(record(1, 18, Long.MAX_VALUE));
        assertNotNull(response);
        assertFalse(ProbeProtocol.currentRequest(response, 1, 100, 5000));
        ProbeProtocol.Record oldest = ProbeProtocol.parse(record(1, 18, 0));
        assertFalse(ProbeProtocol.currentRequest(oldest, 1, Long.MAX_VALUE, 5000));
    }

    @Test public void sameVersionDifferentBuildOrCapabilityCannotPass() {
        String response = record(1, 18, 120).replace(BUILD, "b".repeat(64));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE, evaluate(response, 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE,
                evaluate(record(1, 18, 120).replace(":18:1:", ":18:2:"), 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE,
                evaluate("v2:" + NONCE + ":1:18:120", 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE,
                evaluate("v2:ffffffffffffffffffffffffffffffff:1:18:120", 100, 1, 18, 130));
    }

    @Test public void responseUsesTheLoadedBackendsOwnBuildNotTheRequestsBuild() {
        ProbeProtocol.Record request = ProbeProtocol.parse(record(1, 18, 100));
        String response = ProbeProtocol.response(request, 18, "b".repeat(64), 120);
        assertTrue(ProbeProtocol.currentRequest(request, 1, 120, 5000));
        assertEquals(ProbeProtocol.Status.INCOMPATIBLE, evaluate(response, 100, 1, 18, 130));
    }

    @Test public void missingInvalidExpiredAndFutureResponsesStayDistinct() {
        assertEquals(ProbeProtocol.Status.NO_RESPONSE, evaluate(null, 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.INVALID_RESPONSE, evaluate("malformed", 100, 1, 18, 130));
        assertEquals(ProbeProtocol.Status.EXPIRED, evaluate(record(1, 18, 100), 100, 1, 18, 5101));
        assertEquals(ProbeProtocol.Status.INVALID_RESPONSE, evaluate(record(1, 18, 140), 100, 1, 18, 130));
    }

    private static ProbeProtocol.Status evaluate(String response, long issuedAt, int boot, int version, long now) {
        return ProbeProtocol.evaluate(response, NONCE, issuedAt, boot, version, BUILD, now, 5000);
    }
}
