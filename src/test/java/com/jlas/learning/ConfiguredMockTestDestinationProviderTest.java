package com.jlas.learning;

import com.jlas.learning.config.MockTestProperties;
import com.jlas.learning.dto.MockTestDestination;
import com.jlas.learning.service.impl.ConfiguredMockTestDestinationProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] RC Task 7 — {@link ConfiguredMockTestDestinationProvider}: the only shipped
 * {@code MockTestDestinationProvider}. No real Mock Test owner contract exists in this codebase
 * (re-audited at RC Task 7 time), so this must report {@code BLOCKED_INTEGRATION} whenever nothing is
 * configured, and must reject (never pass through) any configured destination that is not either an
 * internal relative route or an explicitly approved HTTPS origin — the open-redirect guard the RC plan
 * requires. */
class ConfiguredMockTestDestinationProviderTest {

    private MockTestProperties properties() {
        return new MockTestProperties();
    }

    @Test void reportsBlockedIntegrationByDefaultWhenNothingIsConfigured() {
        var provider = new ConfiguredMockTestDestinationProvider(properties());

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
        assertEquals(MockTestDestination.STATUS_BLOCKED_INTEGRATION, destination.status());
        assertNull(destination.url());
        assertEquals("N4", destination.targetLevel());
    }

    @Test void reportsBlockedIntegrationWhenDestinationUrlIsBlank() {
        var props = properties();
        props.setDestinationUrl("   ");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
        assertEquals(MockTestDestination.STATUS_BLOCKED_INTEGRATION, destination.status());
    }

    @Test void acceptsAnInternalRelativeRouteWhenConfigured() {
        var props = properties();
        props.setDestinationUrl("/mock-test/start");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertTrue(destination.available());
        assertEquals(MockTestDestination.STATUS_AVAILABLE, destination.status());
        assertEquals("/mock-test/start", destination.url());
        assertEquals("N4", destination.targetLevel());
    }

    @Test void acceptsAnAbsoluteHttpsUrlWhoseHostIsExplicitlyApproved() {
        var props = properties();
        props.setDestinationUrl("https://mock-test.jlas.example.com/start");
        props.setApprovedOrigins("mock-test.jlas.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N3");

        assertTrue(destination.available());
        assertEquals("https://mock-test.jlas.example.com/start", destination.url());
    }

    @Test void rejectsAnAbsoluteUrlWhoseHostIsNotOnTheApprovedOriginList() {
        var props = properties();
        props.setDestinationUrl("https://evil.example.com/phish");
        props.setApprovedOrigins("mock-test.jlas.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
        assertEquals(MockTestDestination.STATUS_BLOCKED_INTEGRATION, destination.status());
        assertNull(destination.url());
    }

    @Test void rejectsAProtocolRelativeUrlThatWouldSilentlyResolveToAnExternalHost() {
        var props = properties();
        props.setDestinationUrl("//evil.example.com/phish");
        props.setApprovedOrigins("mock-test.jlas.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
        assertEquals(MockTestDestination.STATUS_BLOCKED_INTEGRATION, destination.status());
    }

    @Test void rejectsANonHttpsSchemeEvenOnAnApprovedHost() {
        var props = properties();
        props.setDestinationUrl("http://mock-test.jlas.example.com/start");
        props.setApprovedOrigins("mock-test.jlas.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
    }

    @Test void rejectsAJavascriptSchemeUrl() {
        var props = properties();
        props.setDestinationUrl("javascript:alert(1)");
        props.setApprovedOrigins("mock-test.jlas.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertFalse(destination.available());
    }

    @Test void approvedOriginListSupportsMultipleCommaSeparatedHosts() {
        var props = properties();
        props.setDestinationUrl("https://second.example.com/start");
        props.setApprovedOrigins("first.example.com, second.example.com");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N4");

        assertTrue(destination.available());
    }

    @Test void neverLeaksInternalIdentifiersOnlyEchoesTheTargetLevelThatWasPassedIn() {
        var props = properties();
        props.setDestinationUrl("/mock-test/start");
        var provider = new ConfiguredMockTestDestinationProvider(props);

        MockTestDestination destination = provider.resolveDestination("N2");

        assertEquals("N2", destination.targetLevel());
        // The record has exactly four components: available, status, url, targetLevel — no id/JWT field
        // exists for this seam to leak through even if a future caller passed one in by mistake.
        assertEquals(4, MockTestDestination.class.getRecordComponents().length);
    }
}
