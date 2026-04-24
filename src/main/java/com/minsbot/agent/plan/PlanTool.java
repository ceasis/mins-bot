package com.minsbot.agent.plan;

import com.minsbot.agent.tools.ToolExecutionNotifier;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the model author and update its own task plan. The model should call
 * {@code writePlan} when it starts a non-trivial task and every time a step
 * transitions (e.g. starting a new step, completing the current one).
 *
 * Mirrors Claude Code's TodoWrite: the model re-sends the FULL list each time —
 * no incremental add/update/delete. Forcing a full rewrite is what keeps stale
 * items from accumulating and makes the plan self-correcting.
 */
@Component
public class PlanTool {

    private final PlanService planService;
    private final ToolExecutionNotifier notifier;

    public PlanTool(PlanService planService, ToolExecutionNotifier notifier) {
        this.planService = planService;
        this.notifier = notifier;
    }

    public record TodoInput(String content, String activeForm, String status) {}

    @Tool(description = """
            Create or update the current task plan. Use this for any non-trivial task
            (3+ distinct steps), complex multi-file changes, or when the user gives a
            numbered list of things to do. Skip it for single-step tasks and pure Q&A.

            Pass the FULL list of todos every time — this replaces the plan wholesale.
            Exactly ONE item should have status 'in_progress' at any time. Mark items
            'completed' immediately after finishing them, not in batches. If a step is
            blocked or needs splitting, keep it 'in_progress' and add follow-ups.
            """)
    public String writePlan(
            @ToolParam(description = """
                    The full ordered list of todos. Each item has:
                      content: imperative form — 'Run tests', 'Build the project'
                      activeForm: present continuous — 'Running tests', 'Building the project'
                      status: 'pending' | 'in_progress' | 'completed'
                    """)
            List<TodoInput> todos) {

        if (todos == null) todos = List.of();

        List<PlanService.TodoItem> items = new ArrayList<>(todos.size());
        int inProgress = 0;
        for (TodoInput t : todos) {
            PlanService.Status st;
            try {
                st = PlanService.Status.valueOf(
                        t.status() == null ? "pending" : t.status().toLowerCase());
            } catch (IllegalArgumentException e) {
                return "Invalid status '" + t.status() +
                        "'. Allowed: pending, in_progress, completed.";
            }
            if (st == PlanService.Status.in_progress) inProgress++;
            items.add(new PlanService.TodoItem(t.content(), t.activeForm(), st));
        }

        PlanService.Plan saved = planService.write(items);
        notifier.notify("Plan updated — " + summary(saved));

        StringBuilder hint = new StringBuilder();
        hint.append(summary(saved));
        if (inProgress > 1) {
            hint.append(" (warning: ").append(inProgress)
                    .append(" items are in_progress — exactly one is recommended)");
        } else if (inProgress == 0 && !items.isEmpty()
                && items.stream().anyMatch(i -> i.status() != PlanService.Status.completed)) {
            hint.append(" (warning: no item is in_progress — mark the next one to work on)");
        }
        return hint.toString();
    }

    private static String summary(PlanService.Plan p) {
        int done = 0, active = 0, pending = 0;
        for (var i : p.items()) {
            switch (i.status()) {
                case completed -> done++;
                case in_progress -> active++;
                case pending -> pending++;
            }
        }
        return p.items().size() + " todos (" + done + " done, " + active + " active, "
                + pending + " pending)";
    }
}
