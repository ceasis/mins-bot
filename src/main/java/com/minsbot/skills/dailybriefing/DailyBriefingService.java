package com.minsbot.skills.dailybriefing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minsbot.skills.contentresearch.ContentResearchService;
import com.minsbot.skills.gighunter.GigHunterService;
import com.minsbot.skills.leadgen.LeadGenService;
import com.minsbot.skills.marketresearch.MarketResearchService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Aggregates output from gighunter + leadgen + contentresearch + marketresearch
 * into one ranked morning digest. Stores under memory/briefings/YYYY-MM-DD.json.
 *
 * Each section is optional — pass an empty list to skip.
 */
@Service
public class DailyBriefingService {

    private final DailyBriefingConfig.DailyBriefingProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path dir;

    @Autowired(required = false) private GigHunterService gigHunter;
    @Autowired(required = false) private LeadGenService leadGen;
    @Autowired(required = false) private ContentResearchService contentResearch;
    @Autowired(required = false) private MarketResearchService marketResearch;

    public DailyBriefingService(DailyBriefingConfig.DailyBriefingProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() throws IOException {
        dir = Paths.get(props.getStorageDir());
        Files.createDirectories(dir);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> compile(Map<String, Object> spec) throws IOException {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("skill", "dailybriefing");
        brief.put("date", LocalDate.now().toString());
        List<String> highlights = new ArrayList<>();

        Map<String, Object> gig = (Map<String, Object>) spec.get("gighunter");
        if (gigHunter != null && gig != null) {
            try {
                Map<String, Object> r = gigHunter.run(
                        (List<String>) gig.getOrDefault("keywords", List.of()),
                        (List<String>) gig.getOrDefault("sources", List.of()),
                        (List<String>) gig.getOrDefault("excludeKeywords", List.of()),
                        gig.get("maxResults") instanceof Number n ? n.intValue() : 10);
                brief.put("gighunter", trim(r, "findings", 10));
                int matches = (int) r.getOrDefault("totalMatches", 0);
                if (matches > 0) highlights.add(matches + " new gigs matching your skills");
            } catch (Exception e) { brief.put("gighunterError", e.getMessage()); }
        }

        Map<String, Object> lead = (Map<String, Object>) spec.get("leadgen");
        if (leadGen != null && lead != null) {
            try {
                Map<String, Object> r = leadGen.run(
                        (List<String>) lead.getOrDefault("serviceKeywords", List.of()),
                        (List<String>) lead.getOrDefault("sources", List.of()),
                        lead.get("maxResults") instanceof Number n ? n.intValue() : 10);
                brief.put("leadgen", trim(r, "findings", 10));
                int leads = (int) r.getOrDefault("totalLeads", 0);
                if (leads > 0) highlights.add(leads + " buying-intent leads detected");
            } catch (Exception e) { brief.put("leadgenError", e.getMessage()); }
        }

        Map<String, Object> content = (Map<String, Object>) spec.get("contentresearch");
        if (contentResearch != null && content != null) {
            try {
                Map<String, Object> r = contentResearch.run(
                        (List<String>) content.getOrDefault("topics", List.of()),
                        (List<String>) content.getOrDefault("sources", List.of()),
                        content.get("maxAgeDays") instanceof Number n ? n.intValue() : 7,
                        content.get("maxResults") instanceof Number n ? n.intValue() : 10);
                brief.put("contentresearch", trim(r, "findings", 10));
                List<?> trending = (List<?>) r.get("trendingTopics");
                if (trending != null && !trending.isEmpty()) highlights.add("Trending: "
                        + trending.stream().limit(3).map(Object::toString).reduce((a, b) -> a + ", " + b).orElse(""));
            } catch (Exception e) { brief.put("contentresearchError", e.getMessage()); }
        }

        Map<String, Object> market = (Map<String, Object>) spec.get("marketresearch");
        if (marketResearch != null && market != null) {
            try {
                Map<String, Object> r = marketResearch.run(
                        (List<String>) market.getOrDefault("tickers", List.of()),
                        (List<String>) market.getOrDefault("sources", List.of()),
                        market.get("maxResults") instanceof Number n ? n.intValue() : 10);
                brief.put("marketresearch", trim(r, "findings", 10));
            } catch (Exception e) { brief.put("marketresearchError", e.getMessage()); }
        }

        brief.put("highlights", highlights);
        Path file = dir.resolve(LocalDate.now() + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(brief));
        brief.put("storedAt", file.toAbsolutePath().toString());
        return brief;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trim(Map<String, Object> r, String listKey, int n) {
        Map<String, Object> copy = new LinkedHashMap<>(r);
        Object list = copy.get(listKey);
        if (list instanceof List<?> l && l.size() > n) {
            copy.put(listKey, ((List<Object>) l).subList(0, n));
            copy.put("_trimmed", "showing top " + n);
        }
        return copy;
    }

    public List<String> listBriefings() throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).toList();
        }
    }
}
