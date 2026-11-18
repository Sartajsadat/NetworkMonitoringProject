package gov.mot.netmon.website;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class WebsiteNamesTest {

    @Test
    void acceptsWhatPeopleTypeAndKeepsTheBaseDomain() {
        assertThat(WebsiteNames.normalize("facebook.com")).isEqualTo("facebook.com");
        assertThat(WebsiteNames.normalize(" https://www.Facebook.com/home?x=1 ")).isEqualTo("facebook.com");
        assertThat(WebsiteNames.normalize("m.youtube.com")).isEqualTo("youtube.com");
        assertThat(WebsiteNames.normalize("web.whatsapp.com:443")).isEqualTo("web.whatsapp.com");
        assertThat(WebsiteNames.normalize("*.tiktok.com")).isEqualTo("tiktok.com");
        assertThat(WebsiteNames.normalize("www.com")).isEqualTo("www.com");
        assertThat(WebsiteNames.namesOnDevice("facebook.com")).isEqualTo(List.of("facebook.com", "www.facebook.com", "m.facebook.com"));
    }

    @Test
    void refusesAnythingElse() {
        for (String bad : new String[] { "", "facebook", "10.0.0.1", "http://192.168.1.1/", "localhost", "a b.com", "x@y.com",
                "evil.com\n0.0.0.0 bank.af", "-bad.com" }) {
            assertThat(WebsiteNames.normalize(bad)).as(bad).isNull();
        }
    }
}
