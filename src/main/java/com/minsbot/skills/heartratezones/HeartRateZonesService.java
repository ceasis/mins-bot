package com.minsbot.skills.heartratezones;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HeartRateZonesService {

    public Map<String, Object> zones(int age, Integer restingHr) {
        if (age <= 0 || age > 120) throw new IllegalArgumentException("age 1-120");
        int maxHr = 220 - age;
        List<Map<String, Object>> zones = new ArrayList<>();
        String[] names = {"Zone 1 - Recovery", "Zone 2 - Aerobic", "Zone 3 - Tempo", "Zone 4 - Threshold", "Zone 5 - VO2 Max"};
        double[][] pcts = {{0.50, 0.60}, {0.60, 0.70}, {0.70, 0.80}, {0.80, 0.90}, {0.90, 1.00}};
        String[] descs = {
                "Very easy, recovery & warm-up",
                "Easy, fat-burn, long aerobic",
                "Moderate, cardiovascular, long intervals",
                "Hard, lactate threshold, tempo runs",
                "Max effort, VO2 max intervals"
        };
        for (int i = 0; i < 5; i++) {
            zones.add(Map.of(
                    "zone", i + 1,
                    "name", names[i],
                    "minBpm", (int) Math.round(maxHr * pcts[i][0]),
                    "maxBpm", (int) Math.round(maxHr * pcts[i][1]),
                    "description", descs[i]
            ));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("age", age);
        out.put("maxHrTanaka", (int) Math.round(208 - 0.7 * age));
        out.put("maxHrFormula220", maxHr);
        out.put("method", "220 - age");
        out.put("zones", zones);

        if (restingHr != null && restingHr > 0) {
            List<Map<String, Object>> karvonen = new ArrayList<>();
            int hrr = maxHr - restingHr;
            for (int i = 0; i < 5; i++) {
                karvonen.add(Map.of(
                        "zone", i + 1,
                        "name", names[i],
                        "minBpm", (int) Math.round(hrr * pcts[i][0] + restingHr),
                        "maxBpm", (int) Math.round(hrr * pcts[i][1] + restingHr)
                ));
            }
            out.put("restingHr", restingHr);
            out.put("heartRateReserve", hrr);
            out.put("zonesKarvonen", karvonen);
        }
        return out;
    }
}
