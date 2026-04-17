package com.minsbot.skills.pomodoroplanner;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class PomodoroPlannerService {

    public Map<String, Object> plan(List<Map<String, Object>> tasks, String startTimeStr,
                                    int workMinutes, int shortBreak, int longBreak, int longBreakAfter) {
        if (tasks == null || tasks.isEmpty()) throw new IllegalArgumentException("tasks required");
        if (workMinutes <= 0 || shortBreak < 0 || longBreak < 0 || longBreakAfter <= 0) throw new IllegalArgumentException("invalid timings");

        LocalTime cursor = startTimeStr == null || startTimeStr.isBlank() ? LocalTime.of(9, 0) : LocalTime.parse(startTimeStr);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        List<Map<String, Object>> schedule = new ArrayList<>();
        int pomoCount = 0;
        int totalPomos = 0;

        for (Map<String, Object> task : tasks) {
            String name = String.valueOf(task.getOrDefault("name", "Task"));
            int pomosNeeded = ((Number) task.getOrDefault("pomodoros", 1)).intValue();
            totalPomos += pomosNeeded;
            for (int i = 0; i < pomosNeeded; i++) {
                LocalTime workStart = cursor;
                cursor = cursor.plusMinutes(workMinutes);
                schedule.add(Map.of(
                        "type", "work",
                        "task", name,
                        "pomodoro", (i + 1) + "/" + pomosNeeded,
                        "start", workStart.format(fmt),
                        "end", cursor.format(fmt),
                        "minutes", workMinutes
                ));
                pomoCount++;
                boolean isLong = pomoCount % longBreakAfter == 0;
                int br = isLong ? longBreak : shortBreak;
                if (br > 0) {
                    LocalTime brStart = cursor;
                    cursor = cursor.plusMinutes(br);
                    schedule.add(Map.of(
                            "type", isLong ? "long-break" : "short-break",
                            "start", brStart.format(fmt),
                            "end", cursor.format(fmt),
                            "minutes", br
                    ));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskCount", tasks.size());
        out.put("totalPomodoros", totalPomos);
        out.put("workMinutes", workMinutes);
        out.put("shortBreak", shortBreak);
        out.put("longBreak", longBreak);
        out.put("longBreakAfter", longBreakAfter);
        out.put("totalDurationMinutes", totalPomos * workMinutes + (totalPomos - totalPomos / longBreakAfter) * shortBreak + (totalPomos / longBreakAfter) * longBreak);
        out.put("schedule", schedule);
        return out;
    }
}
