package com.minsbot.skills.meetingcost;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class MeetingCostService {

    public Map<String, Object> computeSimple(int attendees, double avgHourlyRate, int durationMinutes) {
        if (attendees <= 0 || avgHourlyRate < 0 || durationMinutes <= 0) throw new IllegalArgumentException("invalid input");
        double costPerPerson = avgHourlyRate * durationMinutes / 60.0;
        double total = costPerPerson * attendees;
        return Map.of(
                "attendees", attendees,
                "avgHourlyRate", avgHourlyRate,
                "durationMinutes", durationMinutes,
                "costPerAttendee", round(costPerPerson),
                "totalCost", round(total),
                "perMinuteCost", round(total / durationMinutes)
        );
    }

    public Map<String, Object> computeDetailed(List<Map<String, Object>> attendees, int durationMinutes) {
        if (attendees == null || attendees.isEmpty()) throw new IllegalArgumentException("attendees required");
        if (durationMinutes <= 0) throw new IllegalArgumentException("durationMinutes > 0");
        double totalRate = 0;
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Map<String, Object> a : attendees) {
            String name = String.valueOf(a.getOrDefault("name", "?"));
            double rate = ((Number) a.get("hourlyRate")).doubleValue();
            double cost = rate * durationMinutes / 60.0;
            totalRate += rate;
            breakdown.add(Map.of("name", name, "hourlyRate", rate, "cost", round(cost)));
        }
        double totalCost = totalRate * durationMinutes / 60.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("durationMinutes", durationMinutes);
        out.put("attendeeCount", attendees.size());
        out.put("totalCost", round(totalCost));
        out.put("perMinuteCost", round(totalCost / durationMinutes));
        out.put("attendees", breakdown);
        return out;
    }

    private static double round(double v) { return Math.round(v * 100.0) / 100.0; }
}
