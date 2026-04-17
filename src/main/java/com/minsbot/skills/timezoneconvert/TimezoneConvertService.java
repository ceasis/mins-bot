package com.minsbot.skills.timezoneconvert;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class TimezoneConvertService {

    public Map<String, Object> convert(String dateTime, String fromZone, String toZone) {
        try {
            ZoneId fromZ = ZoneId.of(fromZone);
            ZoneId toZ = ZoneId.of(toZone);
            LocalDateTime local = LocalDateTime.parse(dateTime);
            ZonedDateTime source = local.atZone(fromZ);
            ZonedDateTime target = source.withZoneSameInstant(toZ);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            long offsetMinutes = (target.getOffset().getTotalSeconds() - source.getOffset().getTotalSeconds()) / 60;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("from", Map.of("zone", fromZone, "dateTime", source.format(fmt), "offset", source.getOffset().toString()));
            out.put("to", Map.of("zone", toZone, "dateTime", target.format(fmt), "offset", target.getOffset().toString()));
            out.put("offsetDifferenceMinutes", offsetMinutes);
            out.put("epochSeconds", source.toEpochSecond());
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("bad input: " + e.getMessage());
        }
    }

    public Map<String, Object> now(List<String> zones) {
        Instant now = Instant.now();
        Map<String, Object> out = new LinkedHashMap<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (String z : zones) {
            try {
                ZonedDateTime zt = now.atZone(ZoneId.of(z));
                out.put(z, Map.of("dateTime", zt.format(fmt), "offset", zt.getOffset().toString()));
            } catch (Exception e) {
                out.put(z, Map.of("error", "invalid zone"));
            }
        }
        out.put("epochSeconds", now.getEpochSecond());
        return out;
    }

    public List<String> listZones() {
        return ZoneId.getAvailableZoneIds().stream().sorted().toList();
    }
}
