package io.github.selectionmenucontrol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ProbeProtocolOptimizationTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    @Test public void parserRejectsMalformedAndOversizedRequests() {
        for (String value : Arrays.asList(null, "", "v1:" + NONCE + ":1:18:10",
                "v2:pending:0:0:0", "v2:" + NONCE + ":-1:18:10",
                "v2:" + NONCE + ":1:0:10", "v2:" + NONCE + ":1:18:-1",
                "v2:" + NONCE + ":2147483648:18:10",
                "v2:" + NONCE + ":1:18:9223372036854775808",
                "v2:" + NONCE + ":1:18:10:extra", "x".repeat(161))) {
            assertNull(value, ProbeProtocol.parse(value));
        }
        assertNotNull(ProbeProtocol.parse("v2:" + NONCE + ":0:18:0"));
    }

    @Test public void exactValidityBoundaryIsAcceptedButExpiredOrFutureRecordsAreNot() {
        ProbeProtocol.Record record = ProbeProtocol.parse("v2:" + NONCE + ":1:18:100");
        assertTrue(ProbeProtocol.current(record, 1, 18, 100, 5000));
        assertTrue(ProbeProtocol.current(record, 1, 18, 5100, 5000));
        assertFalse(ProbeProtocol.current(record, 1, 18, 5101, 5000));
        assertFalse(ProbeProtocol.current(record, 1, 18, 99, 5000));
    }

    @Test public void responseMustMatchCurrentBootVersionNonceAndRequestTimestamp() {
        ProbeProtocol.Record response = ProbeProtocol.parse("v2:" + NONCE + ":1:18:120");
        assertTrue(ProbeProtocol.matches(response, NONCE, 100, 1, 18, 130, 5000));
        assertFalse(ProbeProtocol.matches(response, NONCE, 121, 1, 18, 130, 5000));
        assertFalse(ProbeProtocol.matches(response, "ffffffffffffffffffffffffffffffff", 100, 1, 18, 130, 5000));
        assertFalse(ProbeProtocol.matches(response, NONCE, 100, 2, 18, 130, 5000));
        assertFalse(ProbeProtocol.matches(response, NONCE, 100, 1, 17, 130, 5000));
        assertFalse(ProbeProtocol.matches(response, NONCE, 100, -1, 18, 130, 5000));
        assertFalse(ProbeProtocol.matches(null, NONCE, 100, 1, 18, 130, 5000));
    }

    @Test public void extremeParsedTimestampCannotOverflowTheFreshnessCheck() {
        ProbeProtocol.Record response = ProbeProtocol.parse("v2:" + NONCE + ":1:18:" + Long.MAX_VALUE);
        assertNotNull(response);
        assertFalse(ProbeProtocol.current(response, 1, 18, 100, 5000));
        ProbeProtocol.Record oldest = ProbeProtocol.parse("v2:" + NONCE + ":1:18:0");
        assertFalse(ProbeProtocol.current(oldest, 1, 18, Long.MAX_VALUE, 5000));
    }
}
