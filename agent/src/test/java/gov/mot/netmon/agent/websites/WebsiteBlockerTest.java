package gov.mot.netmon.agent.websites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import gov.mot.netmon.agent.model.Report;

class WebsiteBlockerTest {

    static final String ORIGINAL = "127.0.0.1 localhost\r\n10.0.0.5 intranet.mot.local\r\n";

    @TempDir
    Path dir;

    @Test
    void sectionIsAddedReadBackAndRemovedWithoutTouchingOtherLines() {
        String blocked = HostsFile.render(ORIGINAL, List.of("facebook.com", "youtube.com"));
        assertTrue(blocked.startsWith(ORIGINAL), "existing lines are kept as they were");
        assertTrue(blocked.contains("0.0.0.0 www.facebook.com\r\n"), "Windows line endings are kept");
        assertTrue(blocked.contains(":: m.youtube.com"));
        assertEquals(List.of("facebook.com", "youtube.com"), HostsFile.blocked(blocked));
        String one = HostsFile.render(blocked, List.of("youtube.com"));
        assertEquals(List.of("youtube.com"), HostsFile.blocked(one));
        assertEquals(1, one.split(java.util.regex.Pattern.quote(HostsFile.BEGIN), -1).length - 1, "only one NetMon section");
        assertEquals(ORIGINAL, HostsFile.render(one, List.of()), "an empty list removes the section completely");
    }

    @Test
    void ignoresAnythingThatIsNotAPlainDomain() {
        assertEquals(List.of("example.org"), HostsFile.sanitize(List.of("Example.org", "10.0.0.1", "localhost", "bad name.com",
                "evil.com\n0.0.0.0 x.com", "example.org")));
        // Lines a user typed outside the section are never reported as NetMon's.
        assertEquals(List.of(), HostsFile.blocked("0.0.0.0 facebook.com www.facebook.com m.facebook.com\n"));
    }

    private WebsiteSettings settings(boolean direct, Path hosts) {
        Properties p = new Properties();
        p.setProperty("websites.enabled", "true");
        p.setProperty("websites.mode", direct ? "direct" : "helper");
        p.setProperty("websites.hosts-file", hosts.toString());
        return WebsiteSettings.fromProperties(p, dir, false);
    }

    @Test
    void directModeWritesTheHostsFileAndReportsWhatIsThere() throws Exception {
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, ORIGINAL);
        AtomicReference<List<String>> want = new AtomicReference<>(null);
        WebsiteBlocker b = new WebsiteBlocker(settings(true, hosts), want::get, Clock.systemUTC());
        // Nothing received from the platform yet: nothing changes.
        Report.Websites s = b.status();
        assertTrue(s.enabled());
        assertEquals(List.of(), s.blocked());
        assertEquals(ORIGINAL, Files.readString(hosts));

        want.set(List.of("facebook.com"));
        s = b.status();
        assertEquals(List.of("facebook.com"), s.blocked());
        assertNull(s.message());

        // Someone deletes the section by hand: it comes back on the next report.
        Files.writeString(hosts, ORIGINAL);
        assertEquals(List.of("facebook.com"), b.status().blocked());

        want.set(List.of());
        assertEquals(List.of(), b.status().blocked());
        assertEquals(ORIGINAL, Files.readString(hosts));
    }

    @Test
    void helperModeOnlyWritesTheRequestAndWarnsWhenTheHelperDoesNotApplyIt() throws Exception {
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, ORIGINAL);
        Instant[] now = { Instant.parse("2026-11-18T10:00:00Z") };
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        WebsiteBlocker b = new WebsiteBlocker(settings(false, hosts), () -> List.of("facebook.com", "x"), clock);
        Report.Websites s = b.status();
        assertEquals(ORIGINAL, Files.readString(hosts), "the unprivileged agent never writes the hosts file itself");
        assertEquals("facebook.com\n", Files.readString(dir.resolve("blocked-sites.txt"), StandardCharsets.UTF_8));
        assertNull(s.message());
        now[0] = now[0].plusSeconds(120);
        assertNotNull(b.status().message(), "helper did not apply it in time");
        // The helper applies it: the warning goes away.
        Files.writeString(hosts, HostsFile.render(ORIGINAL, List.of("facebook.com")));
        s = b.status();
        assertEquals(List.of("facebook.com"), s.blocked());
        assertNull(s.message());
    }

    @Test
    void offUnlessEnabledLocally() throws Exception {
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, ORIGINAL);
        WebsiteBlocker b = new WebsiteBlocker(WebsiteSettings.fromProperties(new Properties(), dir, false), () -> List.of("facebook.com"),
                Clock.systemUTC());
        assertFalse(b.status().enabled());
        b.sync();
        assertEquals(ORIGINAL, Files.readString(hosts));
    }
}
