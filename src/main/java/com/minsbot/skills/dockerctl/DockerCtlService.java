package com.minsbot.skills.dockerctl;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class DockerCtlService {
    private final DockerCtlConfig.DockerCtlProperties props;
    public DockerCtlService(DockerCtlConfig.DockerCtlProperties props) { this.props = props; }

    public Map<String, Object> ps(boolean all) throws Exception {
        return run(all ? new String[]{"docker", "ps", "-a"} : new String[]{"docker", "ps"});
    }
    public Map<String, Object> stop(String name) throws Exception { return run(new String[]{"docker", "stop", name}); }
    public Map<String, Object> start(String name) throws Exception { return run(new String[]{"docker", "start", name}); }
    public Map<String, Object> restart(String name) throws Exception { return run(new String[]{"docker", "restart", name}); }
    public Map<String, Object> logs(String name, int tail) throws Exception {
        return run(new String[]{"docker", "logs", "--tail", String.valueOf(tail), name});
    }
    public Map<String, Object> prune() throws Exception { return run(new String[]{"docker", "system", "prune", "-f"}); }
    public Map<String, Object> images() throws Exception { return run(new String[]{"docker", "images"}); }

    private Map<String, Object> run(String[] cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean done = p.waitFor(props.getTimeoutSec(), TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); return Map.of("ok", false, "error", "timeout"); }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l; while ((l = r.readLine()) != null) sb.append(l).append('\n');
        }
        return Map.of("ok", p.exitValue() == 0, "exitCode", p.exitValue(), "output", sb.toString().trim());
    }
}
