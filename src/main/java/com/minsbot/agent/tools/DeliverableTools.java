package com.minsbot.agent.tools;

import com.minsbot.agent.DeliverableExecutor;
import com.minsbot.agent.DeliverableExecutor.Result;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Single canonical entry to the plan→execute→synthesize→critique→refine loop.
 * The LLM should pick this whenever the user asks for a non-trivial deliverable
 * — a research report, a one-pager, a brief, a memo, a slide outline — that
 * benefits from being researched, drafted, self-critiqued, and revised.
 */
@Component
public class DeliverableTools {

    private final DeliverableExecutor executor;
    private final ToolExecutionNotifier notifier;

    public DeliverableTools(DeliverableExecutor executor, ToolExecutionNotifier notifier) {
        this.executor = executor;
        this.notifier = notifier;
    }

    @Tool(description = "CANONICAL way to produce a RESEARCHED file deliverable (PDF / Word / "
            + "PowerPoint / markdown). Generates the actual .pptx / .docx / .pdf file directly "
            + "via pure-Java writers — DOES NOT need to open PowerPoint, Word, Excel, or any other "
            + "productivity app. Runs plan→research→draft→critique→refine, iterating up to 3 "
            + "times until the critic scores 8+/10. "
            + "USE THIS — DO NOT call openApp(\"powerpoint\"), openApp(\"word\"), openApp(\"excel\"), "
            + "or take screenshots of slides — for ALL of these phrasings: "
            + "  'powerpoint report on X', 'PPT deck about Y', 'pptx of Z', 'slides on Q', "
            + "  'PDF report on R', 'PDF of S', 'word doc / docx on T', 'memo about U', "
            + "  'brief on V', 'compile findings into a W', 'put it in PowerPoint/Word/PDF'. "
            + "ALSO USE WHEN the user has already done research and wants it formatted — pass the "
            + "research goal as the first param, the desired style as second, file format as third. "
            + "Map user wording: 'PowerPoint/PPT/deck/slides' → format='slides', output='pptx'; "
            + "'Word/docx/memo' → format='memo' or 'report', output='docx'; "
            + "'PDF report' → format='report', output='pdf'. "
            + "DO NOT hallucinate other conversion tools (md-to-pdf, pandoc, etc.) — this tool "
            + "owns the markdown→pdf/docx/pptx conversion path end-to-end. "
            + "DO NOT use for quick one-liner answers (use chat directly) or raw search results "
            + "(use webSearchTools.searchWeb / researchTool.research). "
            + "Slow on purpose — multiple LLM round-trips. Returns the file path + score + cycles. "
            + "AFTER this tool returns, STOP. Reply with the file path verbatim — do NOT call "
            + "searchWeb, fetchPageText, saveFinding, or any other research/tool. The deliverable "
            + "ran its own research; further tool calls just confuse the user with extra noise.")
    public String produceDeliverable(
            @ToolParam(description = "Concrete description of what the user wants. "
                    + "Be specific: include subject, scope, audience if known. "
                    + "Example: 'A 2-page brief comparing the top 3 vector databases for a "
                    + "150M-vector workload, focused on cost and Postgres compatibility.'") String goal,
            @ToolParam(description = "Style: 'report' (full sectioned), 'memo' (1-2 page BLUF-first), "
                    + "'brief' (half-page punchy), 'slides' (slide outline, 8-14 slides). "
                    + "Default: 'report'. Pick based on the user's wording — 'deck/PPT/slides' → "
                    + "slides; 'memo' → memo; 'brief' → brief; everything else → report.") String format,
            @ToolParam(description = "Output file format: 'md' (markdown, default), 'pdf', 'docx' (Word), "
                    + "or 'pptx' (PowerPoint, slide-per-section). Match to user's wording: "
                    + "'PDF report' → pdf, 'Word doc' → docx, 'PPT/PowerPoint' → pptx, "
                    + "'just text' → md. If unspecified default to md so the user gets a fast result.") String output) {

        notifier.notify("📦 deliverable: " + truncate(goal, 60));
        String outFmt = (output == null || output.isBlank()) ? "md" : output;
        Result r = executor.produce(goal, format, outFmt);
        if (!r.ok()) return r.message();

        StringBuilder sb = new StringBuilder();
        sb.append("✅ Deliverable ready.\n\n");
        sb.append("File: ").append(r.path().toAbsolutePath()).append("\n");
        sb.append("Score: ").append(r.score()).append("/10  ·  ");
        sb.append("Cycles: ").append(r.cycles());
        if (r.score() < 8) {
            sb.append("\n\nCritic's last note: ").append(r.message());
            sb.append("\n\n(Hit the iteration cap before reaching the 8/10 ship bar — review and "
                    + "ask me to refine further if needed.)");
        }
        sb.append("\n\n[STOP — present this file path to the user. Do not call any more tools.]");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
