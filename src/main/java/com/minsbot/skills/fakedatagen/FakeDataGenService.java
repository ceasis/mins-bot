package com.minsbot.skills.fakedatagen;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FakeDataGenService {

    private static final String[] FIRST_NAMES = {
            "Alex","Alice","Avery","Ben","Cameron","Casey","Chris","Dakota","Drew","Elena",
            "Ethan","Finn","Grace","Hannah","Henry","Isabel","Ivan","Jamie","Jordan","Kai",
            "Leah","Logan","Maya","Morgan","Nora","Owen","Parker","Quinn","Riley","Sam",
            "Sage","Taylor","Uma","Victor","Wren","Xavier","Yara","Zane","Aria","Mason"
    };
    private static final String[] LAST_NAMES = {
            "Adams","Baker","Clark","Davis","Evans","Foster","Green","Hall","Irving","Jones",
            "King","Lewis","Mason","Nelson","Owens","Parker","Quinn","Reed","Smith","Taylor",
            "Ustinov","Vaughn","White","Xu","Young","Zimmer","Morales","Nakamura","Patel","Rossi"
    };
    private static final String[] EMAIL_DOMAINS = {"example.com", "test.org", "demo.io", "sample.net", "fake-mail.dev"};
    private static final String[] COMPANIES = {
            "Acme Corp","Globex","Initech","Hooli","Umbrella","Stark Industries","Wayne Enterprises",
            "Wonka","Pied Piper","Vandelay","Dunder Mifflin","Cyberdyne","Tyrell","Soylent","Aperture"
    };
    private static final String[] STREETS = {"Main St","Oak Ave","Pine Rd","Elm Way","Maple Blvd","Cedar Ln","Birch Dr"};
    private static final String[] CITIES = {"Springfield","Riverside","Franklin","Madison","Georgetown","Clinton","Arlington"};
    private static final String[] STATES = {"CA","NY","TX","FL","IL","PA","OH","GA","NC","MI","WA"};

    private final Random random = new Random();

    public Map<String, Object> generate(List<String> fields, int count, int maxRows) {
        if (count < 1 || count > maxRows) throw new IllegalArgumentException("count 1-" + maxRows);
        if (fields == null || fields.isEmpty()) fields = List.of("name", "email", "phone");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            String firstCached = pick(FIRST_NAMES);
            String lastCached = pick(LAST_NAMES);
            for (String f : fields) {
                row.put(f, generateField(f, firstCached, lastCached));
            }
            rows.add(row);
        }
        return Map.of("count", count, "fields", fields, "rows", rows);
    }

    private Object generateField(String field, String first, String last) {
        return switch (field.toLowerCase()) {
            case "firstname", "first_name" -> first;
            case "lastname", "last_name" -> last;
            case "name", "fullname", "full_name" -> first + " " + last;
            case "email" -> (first + "." + last).toLowerCase() + "@" + pick(EMAIL_DOMAINS);
            case "phone" -> String.format("(%03d) %03d-%04d", 200 + random.nextInt(700), random.nextInt(1000), random.nextInt(10000));
            case "company" -> pick(COMPANIES);
            case "address", "street" -> (100 + random.nextInt(9900)) + " " + pick(STREETS);
            case "city" -> pick(CITIES);
            case "state" -> pick(STATES);
            case "zip" -> String.format("%05d", 10000 + random.nextInt(89999));
            case "country" -> "US";
            case "age" -> 18 + random.nextInt(62);
            case "id", "uuid" -> UUID.randomUUID().toString();
            case "username" -> (first.substring(0, 1) + last + random.nextInt(1000)).toLowerCase();
            case "password" -> randomPassword();
            case "bool", "active" -> random.nextBoolean();
            case "price", "amount" -> Math.round(random.nextDouble() * 10000) / 100.0;
            case "date" -> String.format("2026-%02d-%02d", 1 + random.nextInt(12), 1 + random.nextInt(28));
            default -> "?";
        };
    }

    private String pick(String[] arr) { return arr[random.nextInt(arr.length)]; }

    private String randomPassword() {
        String pool = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(pool.charAt(random.nextInt(pool.length())));
        return sb.toString();
    }

    public List<String> supportedFields() {
        return List.of("firstName", "lastName", "name", "email", "phone", "company", "address", "city", "state", "zip",
                "country", "age", "id", "username", "password", "bool", "price", "date");
    }
}
