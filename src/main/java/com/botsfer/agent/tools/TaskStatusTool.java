package com.botsfer.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TaskStatusTool {

    private final FileTools fileTools;
    private final ToolExecutionNotifier notifier;

    public TaskStatusTool(FileTools fileTools, ToolExecutionNotifier notifier) {
        this.fileTools = fileTools;
        this.notifier = notifier;
    }

    @Tool(description = "Show the status of background tasks like file collection or search")
    public String taskStatus() {
        notifier.notify("Checking task status...");
        Map<String, String> tasks = fileTools.getRunningTasks();
        if (tasks.isEmpty()) {
            return "No tasks running.";
        }
        StringBuilder sb = new StringBuilder("Tasks:\n");
        tasks.forEach((id, status) ->
                sb.append("  ").append(id).append(": ").append(status).append("\n"));
        return sb.toString();
    }
}
