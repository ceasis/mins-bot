package com.minsbot;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans every Spring-managed bean for @Tool methods and renders a single
 * searchable HTML cheat-sheet at /tools.html. Every method, grouped by its
 * declaring class, sorted alphabetically. Auto-updates when new tools are
 * added — no maintenance.
 */
@RestController
public class ToolsCheatSheetController {

    @Autowired
    private ApplicationContext ctx;

    @GetMapping(value = {"/tools.html", "/tools"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> page() {
        Map<String, List<Entry>> byClass = new TreeMap<>();
        int total = 0;
        for (String name : ctx.getBeanDefinitionNames()) {
            Object bean;
            try { bean = ctx.getBean(name); } catch (Exception e) { continue; }
            if (bean == null) continue;
            Class<?> c = org.springframework.aop.support.AopUtils.getTargetClass(bean);
            Method[] methods;
            try { methods = c.getDeclaredMethods(); } catch (Throwable t) { continue; }
            for (Method m : methods) {
                Tool t = m.getAnnotation(Tool.class);
                if (t == null) continue;
                Entry e = new Entry();
                e.clazz = c.getSimpleName();
                e.method = m.getName();
                e.description = t.description();
                e.params = Arrays.stream(m.getParameters())
                        .map(p -> p.getType().getSimpleName() + " " + p.getName())
                        .toList();
                byClass.computeIfAbsent(e.clazz, k -> new java.util.ArrayList<>()).add(e);
                total++;
            }
        }
        for (List<Entry> list : byClass.values()) {
            list.sort(Comparator.comparing(x -> x.method));
        }
        String html = render(byClass, total);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(html);
    }

    private String render(Map<String, List<Entry>> byClass, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=utf-8>");
        sb.append("<title>Mins Bot — Tools</title>");
        sb.append("<meta name=viewport content='width=device-width, initial-scale=1'>");
        sb.append("<style>");
        sb.append("body{margin:0;font:14px/1.6 ui-sans-serif,system-ui,-apple-system,Segoe UI,Roboto,sans-serif;");
        sb.append("background:#0f1115;color:#e6e8ef;padding:24px}");
        sb.append("h1{margin:0 0 4px;font-size:22px;font-weight:600;letter-spacing:-0.01em}");
        sb.append(".sub{color:#8a93a6;font-size:13px;margin-bottom:16px}");
        sb.append("input{width:100%;max-width:700px;background:#1f232c;color:#e6e8ef;");
        sb.append("border:1px solid #2a2f3a;border-radius:8px;padding:10px 14px;font-size:14px;font-family:inherit;margin-bottom:18px}");
        sb.append("input:focus{outline:none;border-color:#7c5cff}");
        sb.append(".grp{margin-bottom:28px}");
        sb.append(".grp h2{font-size:13px;text-transform:uppercase;letter-spacing:0.08em;");
        sb.append("color:#8a93a6;margin:0 0 10px;padding-bottom:6px;border-bottom:1px solid #2a2f3a}");
        sb.append(".tool{padding:10px 12px;border:1px solid #222733;border-radius:8px;margin-bottom:8px;background:#171a21}");
        sb.append(".tool .n{font-family:'JetBrains Mono',Consolas,monospace;font-size:13px;color:#d9dce3}");
        sb.append(".tool .n b{color:#7c5cff;font-weight:600}");
        sb.append(".tool .d{color:#8a93a6;font-size:13px;margin-top:4px}");
        sb.append(".tool .p{font-family:monospace;font-size:11px;color:#5a6072;margin-top:4px}");
        sb.append(".hit b{background:rgba(124,92,255,0.35);color:#fff;border-radius:3px;padding:0 2px}");
        sb.append(".hide{display:none}");
        sb.append("</style></head><body>");
        sb.append("<h1>Mins Bot — Tool reference</h1>");
        sb.append("<div class=sub>").append(total).append(" tools across ").append(byClass.size())
          .append(" components. Type to filter by name, class, or description.</div>");
        sb.append("<input id=q placeholder='Filter (e.g. port, project, github, research) …' autofocus>");
        for (Map.Entry<String, List<Entry>> g : byClass.entrySet()) {
            sb.append("<div class=grp data-grp><h2>").append(g.getKey()).append("</h2>");
            for (Entry e : g.getValue()) {
                String paramsJoined = String.join(", ", e.params);
                sb.append("<div class=tool data-tool data-body=\"")
                  .append(esc((e.clazz + " " + e.method + " " + e.description + " " + paramsJoined).toLowerCase()))
                  .append("\">");
                sb.append("<div class=n><b>").append(e.method).append("</b>(")
                  .append(esc(paramsJoined)).append(")</div>");
                sb.append("<div class=d>").append(esc(e.description)).append("</div>");
                sb.append("</div>");
            }
            sb.append("</div>");
        }
        sb.append("<script>");
        sb.append("const q=document.getElementById('q');");
        sb.append("q.addEventListener('input',()=>{");
        sb.append("const v=q.value.trim().toLowerCase();");
        sb.append("document.querySelectorAll('[data-tool]').forEach(el=>{");
        sb.append("const hit=v===''||el.dataset.body.indexOf(v)>=0;");
        sb.append("el.classList.toggle('hide',!hit);");
        sb.append("});");
        sb.append("document.querySelectorAll('[data-grp]').forEach(g=>{");
        sb.append("const any=[...g.querySelectorAll('[data-tool]')].some(e=>!e.classList.contains('hide'));");
        sb.append("g.classList.toggle('hide',!any);});");
        sb.append("});");
        sb.append("</script></body></html>");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static class Entry {
        String clazz, method, description;
        List<String> params;
    }
}
