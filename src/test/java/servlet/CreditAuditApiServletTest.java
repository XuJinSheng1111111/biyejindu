package servlet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditAuditApiServletTest {

    @Test
    void rejectsCrossSiteRequestsEvenWithoutOrigin() {
        assertFalse(CreditAuditApiServlet.originAllowed(
                "cross-site", null, "http", "localhost", 8080, false));
    }

    @Test
    void acceptsMatchingOriginAndServerPort() {
        assertTrue(CreditAuditApiServlet.originAllowed(
                "same-origin", "http://localhost:8080", "http", "localhost", 8080, false));
    }

    @Test
    void rejectsMismatchedOrigin() {
        assertFalse(CreditAuditApiServlet.originAllowed(
                "same-site", "https://attacker.example", "https", "service.example", 443, true));
    }

    @Test
    void writeRequestRequiresScriptMarkerAndMatchingOrigin() {
        assertFalse(CreditAuditApiServlet.writeRequestAllowed(
                "same-origin", "http://localhost:8080", "http", "localhost", 8080, false, null));
        assertFalse(CreditAuditApiServlet.writeRequestAllowed(
                "cross-site", null, "http", "localhost", 8080, false, "1"));
        assertTrue(CreditAuditApiServlet.writeRequestAllowed(
                "same-origin", "http://localhost:8080", "http", "localhost", 8080, false, "1"));
    }
}
