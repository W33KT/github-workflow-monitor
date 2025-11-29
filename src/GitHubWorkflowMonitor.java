import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author tankaiwen
 */
public class GitHubWorkflowMonitor {

    private static final String STATE_FILE = ".gh_monitor_state";
    private static final int POLL_INTERVAL_SECONDS = 10;
    private static final DateTimeFormatter TIME_PRINTER = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String GITHUB_URL_FORMAT = "https://api.github.com/repos/%s/actions/runs?per_page=10&sort=updated";
    private static final String GITHUB_JOBS_URL_FORMAT = "https://api.github.com/repos/%s/actions/runs/%s/jobs";

    private final String repo;
    private final String token;
    private final HttpClient client;
    private final AtomicBoolean running = new AtomicBoolean(true);

    
    private Instant lastCheckTime;
    private final Path stateFilePath;

    public GitHubWorkflowMonitor(String repo, String token) {
        this.repo = repo;
        this.token = token;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.stateFilePath = Paths.get(STATE_FILE);
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java GitHubWorkflowMonitor.java <owner/repo> <token>");
            System.exit(1);
        }

        GitHubWorkflowMonitor monitor = new GitHubWorkflowMonitor(args[0], args[1]);
        monitor.start();
    }

    public void start() {
        loadState();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Stopping monitor... Saving state.");
            this.running.set(false);
            saveState();
            System.out.println("👋 Bye!");
        }));

        System.out.printf("🚀 Starting monitoring for %s%n", repo);
        System.out.printf("🕒 Monitoring events after: %s%n", TIME_PRINTER.format(lastCheckTime));
        System.out.println("-------------------------------------------------------------------------------------");
        System.out.println("TIME     | STATUS | TYPE | BRANCH       | SHA     | NAME");
        System.out.println("-------------------------------------------------------------------------------------");

        while (running.get()) {
            Instant cycleStartTime = Instant.now();
            try {
                fetchAndProcessEvents();

                lastCheckTime = cycleStartTime;
                saveState(); 

                for (int i = 0; i < POLL_INTERVAL_SECONDS && running.get(); i++) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("⚠️ Error polling GitHub: " + e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    // Ignore
                }
            }
        }
    }

    private void loadState() {
        try {
            if (Files.exists(stateFilePath)) {
                Properties props = new Properties();
                props.load(Files.newInputStream(stateFilePath));
                String savedTime = props.getProperty(repo);
                if (savedTime != null) {
                    lastCheckTime = Instant.parse(savedTime);
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("⚠️ Could not load state, starting fresh.");
        }
        
        lastCheckTime = Instant.now();
    }

    private void saveState() {
        try {
            Properties props = new Properties();

            if (Files.exists(stateFilePath)) {
                props.load(Files.newInputStream(stateFilePath));
            }

            props.setProperty(repo, lastCheckTime.toString());
            props.store(Files.newOutputStream(stateFilePath), "GitHub Monitor State");
        } catch (IOException e) {
            System.err.println("⚠️ Failed to save state: " + e.getMessage());
        }
    }

    private void fetchAndProcessEvents() throws IOException, InterruptedException {
        String url = String.format(GITHUB_URL_FORMAT, repo);
        String response = apiGet(url);

        List<Event> events = new ArrayList<>();

        SimpleJson.Node root = SimpleJson.parse(response);
        List<SimpleJson.Node> runs = root.getArray("workflow_runs");

        for (SimpleJson.Node run : runs) {
            processRun(run, events);
        }

        events.sort(Comparator.comparing(e -> e.timestamp));

        for (Event event : events) {
            if (event.timestamp.isAfter(lastCheckTime)) {
                printEvent(event);
            }
        }
    }

    private void processRun(SimpleJson.Node run, List<Event> events) {
        String runId = String.valueOf(run.getLong("id"));
        String headBranch = run.getString("head_branch");
        String headSha = run.getString("head_sha");
        String runName = run.getString("name");
        
        addEventIfPresent(events, run, "created_at", "queued", "Workflow", headBranch, headSha, runName);
        addEventIfPresent(events, run, "run_started_at", "in_progress", "Workflow", headBranch, headSha, runName);
        addEventIfPresent(events, run, "updated_at", run.getString("status"), "Workflow", headBranch, headSha, runName);

        try {
            String jobsUrl = run.getString("jobs_url");
            if (jobsUrl == null) {
                jobsUrl = String.format(GITHUB_JOBS_URL_FORMAT, repo, runId);
            }

            String jobsResponse = apiGet(jobsUrl);
            List<SimpleJson.Node> jobs = SimpleJson.parse(jobsResponse).getArray("jobs");

            for (SimpleJson.Node job : jobs) {
                String jobName = job.getString("name");
                addEventIfPresent(events, job, "started_at", "in_progress", "Job", headBranch, headSha, jobName);
                addEventIfPresent(events, job, "completed_at", job.getString("conclusion"), "Job", headBranch, headSha, jobName);

                List<SimpleJson.Node> steps = job.getArray("steps");
                for (SimpleJson.Node step : steps) {
                    String stepName = step.getString("name");
                    addEventIfPresent(events, step, "started_at", "in_progress", "Step", headBranch, headSha, stepName);
                    addEventIfPresent(events, step, "completed_at", step.getString("conclusion"), "Step", headBranch, headSha, stepName);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void addEventIfPresent(List<Event> events, SimpleJson.Node node, String timeField, String status, String type, String branch, String sha, String name) {
        String timeStr = node.getString(timeField);
        if (timeStr != null && !timeStr.isEmpty()) {
            Instant timestamp = Instant.parse(timeStr);
            events.add(new Event(timestamp, status, type, branch, sha, name));
        }
    }

    private void printEvent(Event e) {
        String icon;
        String statusColor = "";
        String resetColor = "\u001B[0m";

        String normalizedStatus = e.status == null ? "unknown" : e.status.toLowerCase();

        switch (normalizedStatus) {
            case "success", "completed" -> {
                icon = "🟢 DONE ";
                statusColor = "\u001B[32m";
            }
            case "failure", "timed_out" -> {
                icon = "🔴 FAIL ";
                statusColor = "\u001B[31m";
            }
            case "in_progress", "running" -> {
                icon = "🟡 RUN  ";
                statusColor = "\u001B[33m";
            }
            case "queued" -> {
                icon = "⚪ QUEUE";
                statusColor = "\u001B[37m";
            }
            case "skipped", "cancelled" -> {
                icon = "🚫 SKIP ";
                statusColor = "\u001B[90m";
            }
            default -> icon = "🔹 " + e.status.toUpperCase().substring(0, Math.min(e.status.length(), 4));
        }

        String shortSha = e.sha != null && e.sha.length() > 6 ? e.sha.substring(0, 7) : "unknown";
        String branch = e.branch != null ? e.branch : "HEAD";
        if (branch.length() > 15) {
            branch = branch.substring(0, 12) + "...";
        }

        System.out.printf("%s %s%s%s [%-4s] %-15s (%s) %s%n",
                TIME_PRINTER.format(e.timestamp),
                statusColor, icon, resetColor,
                e.type.substring(0, Math.min(e.type.length(), 4)),
                branch,
                shortSha,
                e.name
        );
    }

    private String apiGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("API Error " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    static class Event {
        Instant timestamp;
        String status;
        String type;
        String branch;
        String sha;
        String name;

        public Event(Instant timestamp, String status, String type, String branch, String sha, String name) {
            this.timestamp = timestamp;
            this.status = status;
            this.type = type;
            this.branch = branch;
            this.sha = sha;
            this.name = name;
        }
    }

    static class SimpleJson {
        static Node parse(String json) { return new Node(json); }

        static class Node {
            private final String src;
            Node(String src) { this.src = src; }

            String getString(String key) {
                Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"(.*?)\"");
                Matcher m = p.matcher(src);
                if (m.find()) {
                    return m.group(1);
                }
                Pattern pNull = Pattern.compile("\"" + key + "\"\\s*:\\s*null");
                if (pNull.matcher(src).find()) {
                    return null;
                }
                return null;
            }

            long getLong(String key) {
                Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)");
                Matcher m = p.matcher(src);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
                return 0;
            }

            List<Node> getArray(String key) {
                List<Node> list = new ArrayList<>();
                int keyIdx = src.indexOf("\"" + key + "\"");
                if (keyIdx == -1) {
                    return list;
                }
                int start = src.indexOf("[", keyIdx);
                if (start == -1) {
                    return list;
                }
                int end = findMatchingBracket(src, start);
                if (end == -1) {
                    return list;
                }
                String arrayContent = src.substring(start + 1, end);
                int bracketCount = 0;
                int objStart = -1;
                for (int i = 0; i < arrayContent.length(); i++) {
                    char c = arrayContent.charAt(i);
                    if (c == '{') {
                        if (bracketCount == 0) {
                            objStart = i;
                        }
                        bracketCount++;
                    } else if (c == '}') {
                        bracketCount--;
                        if (bracketCount == 0 && objStart != -1) {
                            list.add(new Node(arrayContent.substring(objStart, i + 1)));
                            objStart = -1;
                        }
                    }
                }
                return list;
            }

            private int findMatchingBracket(String text, int openPos) {
                int count = 0;
                for (int i = openPos; i < text.length(); i++) {
                    if (text.charAt(i) == '[') {
                        count++;
                    } else if (text.charAt(i) == ']') {
                        count--;
                        if (count == 0) {
                            return i;
                        }
                    }
                }
                return -1;
            }
        }
    }
}