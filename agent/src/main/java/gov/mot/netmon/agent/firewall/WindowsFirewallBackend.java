package gov.mot.netmon.agent.firewall;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.model.Json;

/**
 * Windows Defender Firewall through the bundled script resources/windows/firewall.ps1 (NetSecurity
 * cmdlets). The script text is constant; the validated rule fields are passed in environment
 * variables. Rules: group "NetMon", name "netmon-&lt;tag&gt;". The agent service must run as
 * LocalSystem (or an administrator) to change the firewall. Recovery: {@code Remove-NetFirewallRule -Group NetMon}.
 */
public class WindowsFirewallBackend implements FirewallBackend {

    /** Runs the script with the given environment and returns its JSON output. */
    public interface Script {
        JsonNode run(Map<String, String> env) throws IOException, InterruptedException;
    }

    private final Script script;
    private final String problem;
    private volatile List<String> sessions = List.of();

    public WindowsFirewallBackend(Script script, String problem) {
        this.script = script;
        this.problem = problem;
    }

    @Override
    public String name() {
        return "WINDOWS";
    }

    @Override
    public String problem() {
        return problem;
    }

    static Map<String, String> env(String op, FwRule r) {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("NETMON_FW_OP", op);
        e.put("NETMON_FW_TAG", r.tag());
        e.put("NETMON_FW_DIRECTION", r.inbound() ? "Inbound" : "Outbound");
        e.put("NETMON_FW_ACTION", r.block() ? "Block" : "Allow");
        e.put("NETMON_FW_REMOTE", r.remote());
        if (r.local() != null) e.put("NETMON_FW_LOCAL", r.local());
        e.put("NETMON_FW_PROTOCOL", switch (r.protocol()) {
            case "TCP" -> "TCP";
            case "UDP" -> "UDP";
            case "ICMP" -> r.v6() ? "ICMPv6" : "ICMPv4";
            default -> "Any";
        });
        if (r.localPort() != null) e.put("NETMON_FW_LPORT", r.localPort());
        if (r.remotePort() != null) e.put("NETMON_FW_RPORT", r.remotePort());
        return e;
    }

    @Override
    public String describe(FwRule r) {
        Map<String, String> e = env("apply", r);
        StringBuilder b = new StringBuilder("New-NetFirewallRule -Name netmon-").append(r.tag()).append(" -Group NetMon -Direction ")
                .append(e.get("NETMON_FW_DIRECTION")).append(" -Action ").append(e.get("NETMON_FW_ACTION"))
                .append(" -RemoteAddress ").append(r.remote());
        if (r.local() != null) b.append(" -LocalAddress ").append(r.local());
        if (!"Any".equals(e.get("NETMON_FW_PROTOCOL"))) b.append(" -Protocol ").append(e.get("NETMON_FW_PROTOCOL"));
        if (r.localPort() != null) b.append(" -LocalPort ").append(r.localPort());
        if (r.remotePort() != null) b.append(" -RemotePort ").append(r.remotePort());
        return b.toString();
    }

    private JsonNode call(Map<String, String> env) throws FirewallException {
        try {
            JsonNode out = script.run(env);
            if (!out.path("ok").asBoolean(false)) throw new FirewallException("Windows Firewall: " + out.path("message").asText("failed"));
            return out;
        } catch (IOException e) {
            throw new FirewallException("PowerShell could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FirewallException("interrupted");
        }
    }

    @Override
    public List<String> applicationControl() {
        return problem == null ? List.of("WINDOWS_PROGRAM", "WINDOWS_SERVICE") : List.of();
    }

    /** One block rule per entry; "allow only" / "block internet" become a block of every other address. */
    static List<Map<String, String>> appRules(AppPolicy p) {
        List<Map<String, String>> out = new ArrayList<>();
        if ("BLOCK_DESTINATIONS".equals(p.appMode())) {
            for (AppPolicy.Dest d : p.destinations()) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("remote", d.cidr());
                m.put("protocol", "ANY".equals(d.protocol()) ? "Any" : d.protocol());
                if (d.port() != null) m.put("port", d.port());
                out.add(m);
            }
        } else {
            if (p.destinations().stream().anyMatch(d -> !"ANY".equals(d.protocol()))) {
                throw new IllegalArgumentException("Windows Firewall cannot express allow-only ports for one program");
            }
            out.add(new LinkedHashMap<>(Map.of("remote", String.join(",", AppPolicy.complement(p.allowed())), "protocol", "Any")));
        }
        return out;
    }

    Map<String, String> appEnv(String op, AppPolicy p) throws FirewallException {
        if (!p.identityKind().startsWith("WINDOWS")) throw new FirewallException("Windows Firewall cannot match " + p.identityKind());
        try {
            return Map.of("NETMON_FW_OP", op, "NETMON_FW_TAG", p.tag(), "NETMON_FW_APP_KIND", p.identityKind(), "NETMON_FW_APP_VALUE",
                    p.identity(), "NETMON_FW_APP_RULES", Json.MAPPER.writeValueAsString(appRules(p)));
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new FirewallException(e.getMessage());
        }
    }

    @Override
    public String describeApp(AppPolicy p) {
        String match = "WINDOWS_PROGRAM".equals(p.identityKind()) ? "-Program \"" + p.identity() + "\"" : "-Service " + p.identity();
        StringBuilder b = new StringBuilder();
        int n = 0;
        try {
            for (Map<String, String> r : appRules(p)) {
                b.append("New-NetFirewallRule -Name netmon-").append(p.tag()).append('-').append(++n)
                        .append(" -Group NetMon -Direction Outbound -Action Block ").append(match).append(" -RemoteAddress ").append(r.get("remote"));
                if (!"Any".equals(r.get("protocol"))) b.append(" -Protocol ").append(r.get("protocol"));
                if (r.get("port") != null) b.append(" -RemotePort ").append(r.get("port"));
                b.append('\n');
            }
        } catch (IllegalArgumentException e) {
            return "(" + e.getMessage() + ")";
        }
        return b.toString().trim();
    }

    @Override
    public void checkApp(AppPolicy p) throws FirewallException {
        call(appEnv("check-app", p));
    }

    @Override
    public void applyApp(AppPolicy p) throws FirewallException {
        call(appEnv("apply-app", p));
    }

    @Override
    public void check(FwRule r) throws FirewallException {
        call(env("check", r));
    }

    @Override
    public void apply(FwRule r) throws FirewallException {
        call(env("apply", r));
    }

    @Override
    public Set<String> tags() throws FirewallException {
        JsonNode out = call(Map.of("NETMON_FW_OP", "state"));
        Set<String> s = new HashSet<>();
        for (JsonNode t : out.path("tags")) if (FwRule.TAG.matcher(t.asText()).matches()) s.add(t.asText());
        List<String> sess = new ArrayList<>();
        for (JsonNode a : out.path("sessions")) if (Cidrs.literal(a.asText())) sess.add(a.asText());
        sessions = sess;
        List<String> off = new ArrayList<>();
        for (JsonNode p : out.path("profiles")) if (!p.path("enabled").asBoolean(true)) off.add(p.path("name").asText());
        offProfiles = off;
        return s;
    }

    private volatile List<String> offProfiles = List.of();

    /** Firewall profiles that are switched off (rules are stored but not enforced there). */
    public List<String> offProfiles() {
        return offProfiles;
    }

    @Override
    public boolean remove(String tag) throws FirewallException {
        JsonNode out = call(Map.of("NETMON_FW_OP", "remove", "NETMON_FW_TAG", tag));
        return out.path("found").asBoolean(false);
    }

    @Override
    public List<String> managementSessions() {
        return sessions;
    }

    /** Real runner: Windows PowerShell with the bundled script, no shell, fixed path. */
    public static Script powershell(Path powershell) {
        String encoded = Base64.getEncoder().encodeToString(load().getBytes(StandardCharsets.UTF_16LE));
        return env -> {
            if (!Files.isExecutable(powershell)) throw new java.nio.file.NoSuchFileException(powershell + " not found");
            ProcessBuilder pb = new ProcessBuilder(List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded));
            pb.environment().keySet().removeIf(k -> k.startsWith("NETMON_FW_"));
            pb.environment().putAll(env);
            Process p = pb.start();
            p.getOutputStream().close();
            Thread err = new Thread(() -> { try { p.getErrorStream().readAllBytes(); } catch (IOException ignored) { } });
            err.setDaemon(true);
            err.start();
            byte[] out = p.getInputStream().readNBytes(1024 * 1024);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("PowerShell timed out");
            }
            String s = new String(out, StandardCharsets.UTF_8);
            int start = s.indexOf('{');
            if (start < 0) throw new IOException("PowerShell returned no result");
            return Json.MAPPER.readTree(s.substring(start));
        };
    }

    static String load() {
        try (InputStream in = WindowsFirewallBackend.class.getResourceAsStream("/windows/firewall.ps1")) {
            if (in == null) throw new IllegalStateException("firewall.ps1 missing from agent jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
