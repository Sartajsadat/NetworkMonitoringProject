package gov.mot.netmon.agent.collect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs one fixed, pre-approved executable with an argument list. There is no shell, no string
 * concatenation and no way for the platform to choose the program: the agent never executes
 * arbitrary commands.
 */
public interface CommandRunner {

    record Result(int exitCode, String stdout) {
    }

    Result run(List<String> args) throws IOException, InterruptedException;

    static CommandRunner fixed(Path executable, long timeoutSeconds) {
        return args -> {
            if (!Files.isExecutable(executable)) {
                throw new java.nio.file.NoSuchFileException(executable + " not found or not executable");
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(executable.toString());
            cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readNBytes(64 * 1024);
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(executable.getFileName() + " timed out");
            }
            return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
        };
    }
}
