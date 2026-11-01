package gov.mot.netmon.agent.windows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import gov.mot.netmon.agent.model.Json;

/**
 * Runs the bundled, read-only collection script (resources/windows/collect.ps1) with Windows PowerShell.
 * <ul>
 *   <li>Fixed executable path; argument list, no command shell.</li>
 *   <li>The script is a constant resource passed with -EncodedCommand; nothing from the platform is
 *       inserted into it.</li>
 *   <li>The only input is a list of service names, validated here and passed in an environment variable.</li>
 * </ul>
 */
public class PowerShellQuery implements WindowsQuery {

    /** Windows service names (e.g. W3SVC, MSSQL$SQLEXPRESS). No quotes, spaces or separators. */
    public static final Pattern SERVICE_NAME = Pattern.compile("^[A-Za-z0-9_.$-]{1,128}$");
    static final long TIMEOUT_SECONDS = 60;

    private final Path powershell;
    private final String encodedScript;

    public PowerShellQuery(Path powershell) {
        this.powershell = powershell;
        this.encodedScript = encode(loadScript());
    }

    static String loadScript() {
        try (InputStream in = PowerShellQuery.class.getResourceAsStream("/windows/collect.ps1")) {
            if (in == null) throw new IllegalStateException("collect.ps1 missing from agent jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** -EncodedCommand expects Base64 of UTF-16LE text. */
    static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    static List<String> safeServices(List<String> services) {
        List<String> out = new ArrayList<>();
        for (String s : services) {
            if (s != null && SERVICE_NAME.matcher(s).matches() && out.size() < 100) out.add(s);
        }
        return out;
    }

    List<String> command() {
        return List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encodedScript);
    }

    @Override
    public JsonNode run(List<String> services) throws Exception {
        if (!Files.isExecutable(powershell)) {
            throw new NoSuchFileException(powershell + " not found");
        }
        ProcessBuilder pb = new ProcessBuilder(command()).redirectErrorStream(false);
        pb.environment().put("NETMON_SERVICES", String.join(",", safeServices(services)));
        Process p = pb.start();
        p.getOutputStream().close();
        // Read stderr in the background so a full pipe can never block the process.
        Thread err = new Thread(() -> { try { p.getErrorStream().readAllBytes(); } catch (IOException ignored) { } });
        err.setDaemon(true);
        err.start();
        byte[] out = p.getInputStream().readNBytes(8 * 1024 * 1024);
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("PowerShell data collection timed out after " + TIMEOUT_SECONDS + " s");
        }
        return parse(new String(out, StandardCharsets.UTF_8));
    }

    static JsonNode parse(String stdout) throws IOException {
        int start = stdout.indexOf('{');
        if (start < 0) throw new IOException("PowerShell returned no data");
        return Json.MAPPER.readTree(stdout.substring(start));
    }
}
