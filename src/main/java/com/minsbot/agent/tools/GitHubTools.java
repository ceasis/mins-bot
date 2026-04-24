package com.minsbot.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub REST API v3 integration tools: repos, issues, PRs, gists, notifications, workflows.
 */
@Component
public class GitHubTools {

    private static final Logger log = LoggerFactory.getLogger(GitHubTools.class);
    private static final String API_BASE = "https://api.github.com";
    private static final String TOKEN_MISSING_MSG =
            "GitHub token not configured. Set GITHUB_TOKEN environment variable or app.github.token in application.properties.";

    private final ToolExecutionNotifier notifier;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${app.github.token:${GITHUB_TOKEN:}}")
    private String token;

    public GitHubTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Repository Tools
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "List the authenticated GitHub user's repositories. "
            + "Shows name, description, stars, language, and last updated date. Requires a GitHub token.")
    @com.minsbot.offline.RequiresOnline("GitHub — list repos")
    public String listMyRepos() {
        notifier.notify("Listing your GitHub repositories...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;
            HttpResponse<String> resp = apiGet("/user/repos?sort=updated&per_page=30");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode repos = jsonMapper.readTree(resp.body());
            if (!repos.isArray() || repos.isEmpty()) return "No repositories found.";

            StringBuilder sb = new StringBuilder("Your GitHub repositories:\n");
            int i = 1;
            for (JsonNode repo : repos) {
                sb.append(i++).append(". ").append(text(repo, "full_name"));
                String desc = text(repo, "description");
                if (!desc.isEmpty()) sb.append(" — ").append(desc);
                sb.append("\n   Stars: ").append(repo.path("stargazers_count").asInt());
                sb.append(" | Language: ").append(textOr(repo, "language", "N/A"));
                sb.append(" | Updated: ").append(text(repo, "updated_at").substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get detailed information about a GitHub repository: "
            + "description, stars, forks, open issues, language, license, and default branch.")
    public String getRepoInfo(
            @ToolParam(description = "Repository owner (username or org)") String owner,
            @ToolParam(description = "Repository name") String repo) {
        notifier.notify("Getting info for " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo));
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode r = jsonMapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            sb.append("Repository: ").append(text(r, "full_name")).append("\n");
            sb.append("Description: ").append(textOr(r, "description", "N/A")).append("\n");
            sb.append("Stars: ").append(r.path("stargazers_count").asInt()).append("\n");
            sb.append("Forks: ").append(r.path("forks_count").asInt()).append("\n");
            sb.append("Open Issues: ").append(r.path("open_issues_count").asInt()).append("\n");
            sb.append("Language: ").append(textOr(r, "language", "N/A")).append("\n");
            String license = r.path("license").path("name").asText("N/A");
            sb.append("License: ").append(license).append("\n");
            sb.append("Default Branch: ").append(text(r, "default_branch")).append("\n");
            sb.append("Created: ").append(text(r, "created_at").substring(0, 10)).append("\n");
            sb.append("Updated: ").append(text(r, "updated_at").substring(0, 10)).append("\n");
            sb.append("URL: ").append(text(r, "html_url")).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List branches for a GitHub repository.")
    public String listBranches(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        notifier.notify("Listing branches for " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo) + "/branches?per_page=50");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode branches = jsonMapper.readTree(resp.body());
            if (!branches.isArray() || branches.isEmpty()) return "No branches found.";

            StringBuilder sb = new StringBuilder("Branches for " + owner + "/" + repo + ":\n");
            int i = 1;
            for (JsonNode b : branches) {
                sb.append(i++).append(". ").append(text(b, "name"));
                boolean isProtected = b.path("protected").asBoolean(false);
                if (isProtected) sb.append(" [protected]");
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get the README content of a GitHub repository, decoded from base64.")
    public String getRepoReadme(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        notifier.notify("Fetching README for " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo) + "/readme");
            if (resp.statusCode() == 404) return "No README found for " + owner + "/" + repo + ".";
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode node = jsonMapper.readTree(resp.body());
            String content = text(node, "content").replace("\n", "").replace("\r", "");
            String decoded = new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
            if (decoded.length() > 8000) {
                decoded = decoded.substring(0, 8000) + "\n... (truncated)";
            }
            return "README for " + owner + "/" + repo + ":\n\n" + decoded;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Search GitHub repositories by keyword. Returns top results with name, description, stars, and language.")
    public String searchRepos(
            @ToolParam(description = "Search query, e.g. 'spring boot starter'") String query) {
        notifier.notify("Searching GitHub repos: " + query + "...");
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpResponse<String> resp = apiGet("/search/repositories?q=" + encoded + "&per_page=15&sort=stars");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode root = jsonMapper.readTree(resp.body());
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return "No repositories found for: " + query;

            StringBuilder sb = new StringBuilder("GitHub repo search results for: " + query + "\n");
            sb.append("Total: ").append(root.path("total_count").asInt()).append(" results\n\n");
            int i = 1;
            for (JsonNode r : items) {
                sb.append(i++).append(". ").append(text(r, "full_name"));
                sb.append(" (").append(r.path("stargazers_count").asInt()).append(" stars)");
                String desc = text(r, "description");
                if (!desc.isEmpty()) sb.append("\n   ").append(truncate(desc, 120));
                sb.append("\n   Language: ").append(textOr(r, "language", "N/A"));
                sb.append(" | ").append(text(r, "html_url")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new GitHub repository under the authenticated user's account. "
            + "Requires a GitHub token with 'repo' scope. Returns the new repo's URL on success.")
    @com.minsbot.offline.RequiresOnline("GitHub — create repo")
    public String createRepo(
            @ToolParam(description = "Repository name (e.g. 'my-new-repo')") String name,
            @ToolParam(description = "Short description (optional, pass empty string if none)") String description,
            @ToolParam(description = "True for private repo, false for public") boolean isPrivate,
            @ToolParam(description = "True to initialize with an empty README") boolean autoInit) {
        notifier.notify("Creating GitHub repository: " + name + "...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;
            if (name == null || name.isBlank()) return "Error: repository name is required.";

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name.trim());
            if (description != null && !description.isBlank()) payload.put("description", description);
            payload.put("private", isPrivate);
            payload.put("auto_init", autoInit);

            HttpResponse<String> resp = apiPost("/user/repos", jsonMapper.writeValueAsString(payload));
            if (resp.statusCode() != 201) return apiError(resp);

            JsonNode repo = jsonMapper.readTree(resp.body());
            return "Repository created: " + text(repo, "full_name")
                    + (isPrivate ? " [private]" : " [public]")
                    + "\nURL: " + text(repo, "html_url")
                    + "\nClone: " + text(repo, "clone_url");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Issue & PR Tools
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "List issues for a GitHub repository. Shows title, number, labels, assignee, and created date.")
    public String listIssues(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue state: open, closed, or all") String state) {
        notifier.notify("Listing " + state + " issues for " + owner + "/" + repo + "...");
        try {
            String s = (state == null || state.isBlank()) ? "open" : state.toLowerCase();
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/issues?state=" + enc(s) + "&per_page=30");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode issues = jsonMapper.readTree(resp.body());
            if (!issues.isArray() || issues.isEmpty()) return "No " + s + " issues found for " + owner + "/" + repo + ".";

            StringBuilder sb = new StringBuilder();
            sb.append(capitalize(s)).append(" issues for ").append(owner).append("/").append(repo).append(":\n");
            int i = 1;
            for (JsonNode issue : issues) {
                // Skip pull requests (they appear in the issues endpoint too)
                if (issue.has("pull_request")) continue;

                sb.append(i++).append(". #").append(issue.path("number").asInt());
                sb.append(" — ").append(text(issue, "title"));

                // Labels
                JsonNode labels = issue.path("labels");
                if (labels.isArray() && !labels.isEmpty()) {
                    sb.append(" [");
                    for (int j = 0; j < labels.size(); j++) {
                        if (j > 0) sb.append(", ");
                        sb.append(text(labels.get(j), "name"));
                    }
                    sb.append("]");
                }

                // Assignee
                String assignee = issue.path("assignee").path("login").asText("");
                sb.append(" (assigned to: ").append(assignee.isEmpty() ? "unassigned" : assignee).append(")");

                // Date
                String created = text(issue, "created_at");
                if (created.length() >= 10) sb.append(" — ").append(created.substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new issue on a GitHub repository. Requires a GitHub token with repo access.")
    public String createIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue title") String title,
            @ToolParam(description = "Issue body/description") String body,
            @ToolParam(description = "Comma-separated labels, e.g. 'bug,critical'") String labels) {
        notifier.notify("Creating issue on " + owner + "/" + repo + "...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            if (body != null && !body.isBlank()) payload.put("body", body);
            if (labels != null && !labels.isBlank()) {
                payload.put("labels", labels.split("\\s*,\\s*"));
            }

            HttpResponse<String> resp = apiPost("/repos/" + enc(owner) + "/" + enc(repo) + "/issues",
                    jsonMapper.writeValueAsString(payload));
            if (resp.statusCode() != 201) return apiError(resp);

            JsonNode issue = jsonMapper.readTree(resp.body());
            return "Issue created: #" + issue.path("number").asInt()
                    + " — " + text(issue, "title")
                    + "\nURL: " + text(issue, "html_url");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get details of a specific GitHub issue including comments.")
    public String getIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue number") int number) {
        notifier.notify("Getting issue #" + number + " from " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo) + "/issues/" + number);
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode issue = jsonMapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            sb.append("Issue #").append(number).append(": ").append(text(issue, "title")).append("\n");
            sb.append("State: ").append(text(issue, "state")).append("\n");
            sb.append("Author: ").append(issue.path("user").path("login").asText("unknown")).append("\n");
            sb.append("Created: ").append(text(issue, "created_at").substring(0, 10)).append("\n");

            String assignee = issue.path("assignee").path("login").asText("");
            sb.append("Assignee: ").append(assignee.isEmpty() ? "unassigned" : assignee).append("\n");

            JsonNode labels = issue.path("labels");
            if (labels.isArray() && !labels.isEmpty()) {
                sb.append("Labels: ");
                for (int j = 0; j < labels.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(text(labels.get(j), "name"));
                }
                sb.append("\n");
            }

            String bodyText = text(issue, "body");
            if (!bodyText.isEmpty()) {
                sb.append("\n--- Body ---\n");
                sb.append(truncate(bodyText, 3000)).append("\n");
            }

            // Fetch comments
            int commentCount = issue.path("comments").asInt(0);
            if (commentCount > 0) {
                HttpResponse<String> commentsResp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                        + "/issues/" + number + "/comments?per_page=20");
                if (commentsResp.statusCode() == 200) {
                    JsonNode comments = jsonMapper.readTree(commentsResp.body());
                    sb.append("\n--- Comments (").append(commentCount).append(") ---\n");
                    for (JsonNode c : comments) {
                        sb.append("\n").append(c.path("user").path("login").asText("unknown"));
                        sb.append(" (").append(text(c, "created_at").substring(0, 10)).append("):\n");
                        sb.append(truncate(text(c, "body"), 500)).append("\n");
                    }
                }
            }

            sb.append("\nURL: ").append(text(issue, "html_url"));
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Add a comment to a GitHub issue or pull request.")
    public String commentOnIssue(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Issue or PR number") int number,
            @ToolParam(description = "Comment text") String comment) {
        notifier.notify("Commenting on #" + number + " in " + owner + "/" + repo + "...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("body", comment);

            HttpResponse<String> resp = apiPost("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/issues/" + number + "/comments", jsonMapper.writeValueAsString(payload));
            if (resp.statusCode() != 201) return apiError(resp);

            JsonNode c = jsonMapper.readTree(resp.body());
            return "Comment added to #" + number + "\nURL: " + text(c, "html_url");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List pull requests for a GitHub repository with title, number, author, and status.")
    public String listPullRequests(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "PR state: open, closed, or all") String state) {
        notifier.notify("Listing " + state + " PRs for " + owner + "/" + repo + "...");
        try {
            String s = (state == null || state.isBlank()) ? "open" : state.toLowerCase();
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/pulls?state=" + enc(s) + "&per_page=30");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode prs = jsonMapper.readTree(resp.body());
            if (!prs.isArray() || prs.isEmpty()) return "No " + s + " pull requests found for " + owner + "/" + repo + ".";

            StringBuilder sb = new StringBuilder();
            sb.append(capitalize(s)).append(" pull requests for ").append(owner).append("/").append(repo).append(":\n");
            int i = 1;
            for (JsonNode pr : prs) {
                sb.append(i++).append(". #").append(pr.path("number").asInt());
                sb.append(" — ").append(text(pr, "title"));
                sb.append(" (by ").append(pr.path("user").path("login").asText("unknown")).append(")");
                sb.append(" [").append(text(pr, "state")).append("]");
                if (pr.path("draft").asBoolean(false)) sb.append(" [draft]");
                String created = text(pr, "created_at");
                if (created.length() >= 10) sb.append(" — ").append(created.substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get details of a specific pull request including diff stats, reviewers, and merge status.")
    public String getPullRequest(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "PR number") int number) {
        notifier.notify("Getting PR #" + number + " from " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo) + "/pulls/" + number);
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode pr = jsonMapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            sb.append("PR #").append(number).append(": ").append(text(pr, "title")).append("\n");
            sb.append("State: ").append(text(pr, "state"));
            if (pr.path("merged").asBoolean(false)) sb.append(" (merged)");
            if (pr.path("draft").asBoolean(false)) sb.append(" [draft]");
            sb.append("\n");
            sb.append("Author: ").append(pr.path("user").path("login").asText("unknown")).append("\n");
            sb.append("Branch: ").append(pr.path("head").path("ref").asText("?"))
                    .append(" -> ").append(pr.path("base").path("ref").asText("?")).append("\n");
            sb.append("Created: ").append(text(pr, "created_at").substring(0, 10)).append("\n");

            sb.append("Commits: ").append(pr.path("commits").asInt()).append("\n");
            sb.append("Changed Files: ").append(pr.path("changed_files").asInt()).append("\n");
            sb.append("Additions: +").append(pr.path("additions").asInt()).append("\n");
            sb.append("Deletions: -").append(pr.path("deletions").asInt()).append("\n");
            sb.append("Mergeable: ").append(pr.path("mergeable").asText("unknown")).append("\n");

            // Reviewers
            JsonNode reviewers = pr.path("requested_reviewers");
            if (reviewers.isArray() && !reviewers.isEmpty()) {
                sb.append("Requested Reviewers: ");
                for (int j = 0; j < reviewers.size(); j++) {
                    if (j > 0) sb.append(", ");
                    sb.append(reviewers.get(j).path("login").asText("?"));
                }
                sb.append("\n");
            }

            String bodyText = text(pr, "body");
            if (!bodyText.isEmpty()) {
                sb.append("\n--- Description ---\n");
                sb.append(truncate(bodyText, 3000)).append("\n");
            }

            sb.append("\nURL: ").append(text(pr, "html_url"));
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new pull request on a GitHub repository. Requires a GitHub token with repo access.")
    public String createPullRequest(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "PR title") String title,
            @ToolParam(description = "PR body/description") String body,
            @ToolParam(description = "Head branch (source branch with changes)") String head,
            @ToolParam(description = "Base branch (target branch to merge into)") String base) {
        notifier.notify("Creating PR on " + owner + "/" + repo + "...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("head", head);
            payload.put("base", base);
            if (body != null && !body.isBlank()) payload.put("body", body);

            HttpResponse<String> resp = apiPost("/repos/" + enc(owner) + "/" + enc(repo) + "/pulls",
                    jsonMapper.writeValueAsString(payload));
            if (resp.statusCode() != 201) return apiError(resp);

            JsonNode pr = jsonMapper.readTree(resp.body());
            return "Pull request created: #" + pr.path("number").asInt()
                    + " — " + text(pr, "title")
                    + "\nURL: " + text(pr, "html_url");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Activity Tools
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "List unread GitHub notifications for the authenticated user.")
    public String getNotifications() {
        notifier.notify("Fetching GitHub notifications...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;
            HttpResponse<String> resp = apiGet("/notifications?per_page=30");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode notifications = jsonMapper.readTree(resp.body());
            if (!notifications.isArray() || notifications.isEmpty()) return "No unread notifications.";

            StringBuilder sb = new StringBuilder("Unread GitHub notifications:\n");
            int i = 1;
            for (JsonNode n : notifications) {
                sb.append(i++).append(". ");
                sb.append("[").append(text(n.path("subject"), "type")).append("] ");
                sb.append(text(n.path("subject"), "title"));
                sb.append(" (").append(text(n.path("repository"), "full_name")).append(")");
                sb.append(" — ").append(text(n, "reason"));
                String updated = text(n, "updated_at");
                if (updated.length() >= 10) sb.append(" — ").append(updated.substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get recent activity/events for the authenticated GitHub user.")
    public String getMyActivity() {
        notifier.notify("Fetching your GitHub activity...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;

            // First get the authenticated user's login
            HttpResponse<String> userResp = apiGet("/user");
            if (userResp.statusCode() != 200) return apiError(userResp);
            String login = jsonMapper.readTree(userResp.body()).path("login").asText("");
            if (login.isEmpty()) return "Could not determine authenticated user.";

            HttpResponse<String> resp = apiGet("/users/" + enc(login) + "/events?per_page=20");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode events = jsonMapper.readTree(resp.body());
            if (!events.isArray() || events.isEmpty()) return "No recent activity found.";

            StringBuilder sb = new StringBuilder("Recent GitHub activity for " + login + ":\n");
            int i = 1;
            for (JsonNode e : events) {
                sb.append(i++).append(". ");
                sb.append(text(e, "type").replace("Event", ""));
                sb.append(" on ").append(text(e.path("repo"), "name"));
                String created = text(e, "created_at");
                if (created.length() >= 10) sb.append(" — ").append(created.substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "List the authenticated user's GitHub gists.")
    public String listGists() {
        notifier.notify("Listing your gists...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;
            HttpResponse<String> resp = apiGet("/gists?per_page=20");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode gists = jsonMapper.readTree(resp.body());
            if (!gists.isArray() || gists.isEmpty()) return "No gists found.";

            StringBuilder sb = new StringBuilder("Your GitHub gists:\n");
            int i = 1;
            for (JsonNode g : gists) {
                sb.append(i++).append(". ");
                String desc = text(g, "description");
                sb.append(desc.isEmpty() ? "(no description)" : desc);
                sb.append(g.path("public").asBoolean() ? " [public]" : " [private]");

                // List filenames
                JsonNode files = g.path("files");
                if (files.isObject()) {
                    sb.append(" — Files: ");
                    var fieldNames = files.fieldNames();
                    int count = 0;
                    while (fieldNames.hasNext() && count < 3) {
                        if (count > 0) sb.append(", ");
                        sb.append(fieldNames.next());
                        count++;
                    }
                }

                String updated = text(g, "updated_at");
                if (updated.length() >= 10) sb.append(" — ").append(updated.substring(0, 10));
                sb.append("\n   ").append(text(g, "html_url")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new GitHub gist with a single file. Requires a GitHub token.")
    public String createGist(
            @ToolParam(description = "Gist description") String description,
            @ToolParam(description = "Filename, e.g. 'snippet.py'") String filename,
            @ToolParam(description = "File content") String content,
            @ToolParam(description = "True for public gist, false for private") boolean isPublic) {
        notifier.notify("Creating gist: " + filename + "...");
        try {
            if (!hasToken()) return TOKEN_MISSING_MSG;

            Map<String, Object> fileObj = new LinkedHashMap<>();
            fileObj.put("content", content);
            Map<String, Object> files = new LinkedHashMap<>();
            files.put(filename, fileObj);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("description", description != null ? description : "");
            payload.put("public", isPublic);
            payload.put("files", files);

            HttpResponse<String> resp = apiPost("/gists", jsonMapper.writeValueAsString(payload));
            if (resp.statusCode() != 201) return apiError(resp);

            JsonNode gist = jsonMapper.readTree(resp.body());
            return "Gist created: " + text(gist, "html_url");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Workflow Tools
    // ═════════════════════════════════════════════════════════════════════════

    @Tool(description = "List recent CI/CD workflow runs for a GitHub repository, showing status and conclusion.")
    public String listWorkflowRuns(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo) {
        notifier.notify("Listing workflow runs for " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/actions/runs?per_page=15");
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode root = jsonMapper.readTree(resp.body());
            JsonNode runs = root.path("workflow_runs");
            if (!runs.isArray() || runs.isEmpty()) return "No workflow runs found for " + owner + "/" + repo + ".";

            StringBuilder sb = new StringBuilder("Recent workflow runs for " + owner + "/" + repo + ":\n");
            int i = 1;
            for (JsonNode run : runs) {
                sb.append(i++).append(". ");
                sb.append(text(run, "name"));
                sb.append(" #").append(run.path("run_number").asInt());
                sb.append(" [").append(text(run, "status"));
                String conclusion = text(run, "conclusion");
                if (!conclusion.isEmpty() && !conclusion.equals("null")) sb.append("/").append(conclusion);
                sb.append("]");
                sb.append(" — branch: ").append(text(run, "head_branch"));
                String created = text(run, "created_at");
                if (created.length() >= 10) sb.append(" — ").append(created.substring(0, 10));
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(description = "Get details and log summary for a specific GitHub Actions workflow run.")
    public String getWorkflowRunStatus(
            @ToolParam(description = "Repository owner") String owner,
            @ToolParam(description = "Repository name") String repo,
            @ToolParam(description = "Workflow run ID") long runId) {
        notifier.notify("Getting workflow run " + runId + " from " + owner + "/" + repo + "...");
        try {
            HttpResponse<String> resp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/actions/runs/" + runId);
            if (resp.statusCode() != 200) return apiError(resp);

            JsonNode run = jsonMapper.readTree(resp.body());
            StringBuilder sb = new StringBuilder();
            sb.append("Workflow Run #").append(run.path("run_number").asInt()).append("\n");
            sb.append("Name: ").append(text(run, "name")).append("\n");
            sb.append("Status: ").append(text(run, "status")).append("\n");
            String conclusion = text(run, "conclusion");
            if (!conclusion.isEmpty() && !conclusion.equals("null")) {
                sb.append("Conclusion: ").append(conclusion).append("\n");
            }
            sb.append("Branch: ").append(text(run, "head_branch")).append("\n");
            sb.append("Event: ").append(text(run, "event")).append("\n");
            sb.append("Created: ").append(text(run, "created_at")).append("\n");
            sb.append("Updated: ").append(text(run, "updated_at")).append("\n");
            sb.append("URL: ").append(text(run, "html_url")).append("\n");

            // Fetch jobs for this run
            HttpResponse<String> jobsResp = apiGet("/repos/" + enc(owner) + "/" + enc(repo)
                    + "/actions/runs/" + runId + "/jobs?per_page=20");
            if (jobsResp.statusCode() == 200) {
                JsonNode jobs = jsonMapper.readTree(jobsResp.body()).path("jobs");
                if (jobs.isArray() && !jobs.isEmpty()) {
                    sb.append("\nJobs:\n");
                    for (JsonNode job : jobs) {
                        sb.append("  - ").append(text(job, "name"));
                        sb.append(" [").append(text(job, "status"));
                        String jConclusion = text(job, "conclusion");
                        if (!jConclusion.isEmpty() && !jConclusion.equals("null"))
                            sb.append("/").append(jConclusion);
                        sb.append("]\n");

                        // Steps
                        JsonNode steps = job.path("steps");
                        if (steps.isArray()) {
                            for (JsonNode step : steps) {
                                sb.append("    ").append(step.path("number").asInt()).append(". ");
                                sb.append(text(step, "name"));
                                sb.append(" [").append(text(step, "status"));
                                String sConclusion = text(step, "conclusion");
                                if (!sConclusion.isEmpty() && !sConclusion.equals("null"))
                                    sb.append("/").append(sConclusion);
                                sb.append("]\n");
                            }
                        }
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HTTP Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private HttpResponse<String> apiGet(String path) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "MinsBot")
                .timeout(Duration.ofSeconds(15))
                .GET();
        if (hasToken()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> apiPost(String path, String jsonBody) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "MinsBot")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (hasToken()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Utility Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private boolean hasToken() {
        return token != null && !token.isBlank();
    }

    private String apiError(HttpResponse<String> resp) {
        try {
            JsonNode body = jsonMapper.readTree(resp.body());
            String msg = text(body, "message");
            if (!msg.isEmpty()) return "GitHub API error (" + resp.statusCode() + "): " + msg;
        } catch (Exception ignored) {}
        return "GitHub API error: HTTP " + resp.statusCode();
    }

    private static String text(JsonNode node, String field) {
        JsonNode val = node.path(field);
        return val.isTextual() ? val.asText("") : "";
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String val = text(node, field);
        return val.isEmpty() ? fallback : val;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
