package gov.mot.netmon.agent.sites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostsFileTest {

    static final String ORIGINAL = "# Copyright (c) Microsoft\r\n127.0.0.1 localhost\r\n::1 localhost\r\n";

    @Test
    void addsSectionAndKeepsEverythingElse() {
        String out = HostsFile.render(ORIGINAL, List.of("youtube.com"));
        assertTrue(out.startsWith(ORIGINAL), out);
        assertTrue(out.contains("0.0.0.0 youtube.com\r\n"));
        assertTrue(out.contains("0.0.0.0 www.youtube.com\r\n"));
        assertTrue(out.contains(":: m.youtube.com\r\n"));
        assertEquals(List.of("youtube.com"), HostsFile.blocked(out));
    }

    @Test
    void emptyListRemovesSectionAndRestoresOriginal() {
        String blocked = HostsFile.render(ORIGINAL, List.of("a.com", "b.org"));
        assertEquals(List.of("a.com", "b.org"), HostsFile.blocked(blocked));
        assertEquals(ORIGINAL, HostsFile.render(blocked, List.of()));
        assertEquals(ORIGINAL, HostsFile.render(ORIGINAL, List.of()));
    }

    @Test
    void replacingTheListRewritesTheWholeSection() {
        String one = HostsFile.render("127.0.0.1 localhost\n", List.of("a.com", "b.com"));
        String two = HostsFile.render(one, List.of("b.com"));
        assertEquals(List.of("b.com"), HostsFile.blocked(two));
        assertFalse(two.contains("a.com"));
        assertEquals(1, two.split(java.util.regex.Pattern.quote(HostsFile.BEGIN), -1).length - 1);
    }

    @Test
    void prefixedSiteIsReportedAsGiven() {
        String out = HostsFile.render("", List.of("www.example.com"));
        assertEquals(List.of("www.example.com"), HostsFile.blocked(out));
        assertFalse(out.contains("www.www."));
    }

    @Test
    void cleanDropsInvalidNames() {
        assertEquals(List.of("good.com", "x.y.org"), HostsFile.clean(List.of("GOOD.com", "x.y.org", "*.bad.com", "1.2.3.4",
                "no_dots", "bad name.com", "evil.com\n0.0.0.0 bank.com", "good.com")));
        assertFalse(HostsFile.valid("localhost"));
        assertFalse(HostsFile.valid("-a.com"));
    }

    @Test
    void blockerWritesOnlyWhenTheListChanges(@TempDir Path dir) throws IOException {
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        List<List<String>> writes = new ArrayList<>();
        SiteBlocker b = new SiteBlocker(new SiteSettings(true, hosts, null, null, null), (content, domains) -> {
            writes.add(domains);
            Files.writeString(hosts, content, StandardCharsets.ISO_8859_1);
        });
        b.apply(null);                      // no list from the platform yet: untouched
        assertEquals(0, writes.size());
        b.apply(List.of("youtube.com"));
        b.apply(List.of("YouTube.com"));    // same list: no second write
        assertEquals(1, writes.size());
        assertEquals(List.of("youtube.com"), b.status().blocked());
        b.apply(List.of());
        assertEquals(List.of(), b.status().blocked());
        assertEquals("127.0.0.1 localhost\n", Files.readString(hosts));
    }

    @Test
    void disabledBlockerNeverWrites(@TempDir Path dir) throws IOException {
        Path hosts = dir.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        SiteBlocker b = new SiteBlocker(new SiteSettings(false, hosts, null, null, null), (c, d) -> {
            throw new AssertionError("must not write");
        });
        b.apply(List.of("youtube.com"));
        assertFalse(b.status().enabled());
    }
}
