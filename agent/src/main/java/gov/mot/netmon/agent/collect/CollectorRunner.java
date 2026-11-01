package gov.mot.netmon.agent.collect;

import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import gov.mot.netmon.agent.model.Report;

/**
 * Runs every collector independently: one failing collector never stops the others, and each
 * failure is reported to the platform with an explicit status instead of fake values.
 */
public class CollectorRunner {

    private static final Logger log = Logger.getLogger(CollectorRunner.class.getName());

    private final List<Collector> collectors;

    public CollectorRunner(List<Collector> collectors) {
        this.collectors = List.copyOf(collectors);
    }

    public ReportBuilder run(Instant now) {
        ReportBuilder b = new ReportBuilder();
        for (Collector c : collectors) {
            try {
                c.collect(b, now);
                b.collectors.add(new Report.CollectorStatus(c.name(), "OK", null));
            } catch (UnsupportedOperationException e) {
                b.collectors.add(new Report.CollectorStatus(c.name(), "UNSUPPORTED", trim(e.getMessage())));
            } catch (AccessDeniedException | SecurityException e) {
                b.collectors.add(new Report.CollectorStatus(c.name(), "PERMISSION_DENIED", trim(e.getMessage())));
            } catch (NoSuchFileException | FileNotFoundException e) {
                b.collectors.add(new Report.CollectorStatus(c.name(), "UNAVAILABLE", trim(e.getMessage())));
            } catch (Exception e) {
                log.log(Level.WARNING, "Collector " + c.name() + " failed", e);
                b.collectors.add(new Report.CollectorStatus(c.name(), "ERROR",
                        trim(e.getClass().getSimpleName() + ": " + e.getMessage())));
            }
        }
        return b;
    }

    private static String trim(String s) {
        if (s == null) return null;
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
