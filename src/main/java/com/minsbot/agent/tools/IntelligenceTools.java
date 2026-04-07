package com.minsbot.agent.tools;

import com.minsbot.agent.AsyncMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Intelligence layer — Jarvis-style proactive reasoning tools.
 * <ul>
 *   <li>Daily briefing generator (weather + calendar + tasks + emails + health + bills)</li>
 *   <li>Decision helper (purchase analysis against budget/goals/spending)</li>
 *   <li>Conflict detector (calendar overlap scanner)</li>
 *   <li>Travel planner (comprehensive trip planning orchestrator)</li>
 * </ul>
 */
@Component
public class IntelligenceTools {

    private static final Logger log = LoggerFactory.getLogger(IntelligenceTools.class);

    private final ToolExecutionNotifier notifier;
    private final CalendarTools calendarTools;
    private final GmailApiTools gmailApiTools;
    private final WeatherTools weatherTools;
    private final HealthTrackerTools healthTrackerTools;
    private final FinanceTrackerTools financeTrackerTools;
    private final TtsTools ttsTools;
    private final WebSearchTools webSearchTools;
    private final TravelSearchTools travelSearchTools;
    private final TodoListTools todoListTools;
    private final AsyncMessageService asyncMessages;

    public IntelligenceTools(ToolExecutionNotifier notifier,
                             CalendarTools calendarTools,
                             GmailApiTools gmailApiTools,
                             WeatherTools weatherTools,
                             HealthTrackerTools healthTrackerTools,
                             FinanceTrackerTools financeTrackerTools,
                             TtsTools ttsTools,
                             WebSearchTools webSearchTools,
                             TravelSearchTools travelSearchTools,
                             TodoListTools todoListTools,
                             AsyncMessageService asyncMessages) {
        this.notifier = notifier;
        this.calendarTools = calendarTools;
        this.gmailApiTools = gmailApiTools;
        this.weatherTools = weatherTools;
        this.healthTrackerTools = healthTrackerTools;
        this.financeTrackerTools = financeTrackerTools;
        this.ttsTools = ttsTools;
        this.webSearchTools = webSearchTools;
        this.travelSearchTools = travelSearchTools;
        this.todoListTools = todoListTools;
        this.asyncMessages = asyncMessages;
    }

    // ═══ 1. Daily Briefing Generator ═══

    @Tool(description = "Generate a comprehensive daily briefing covering: weather forecast, today's calendar events, "
            + "pending tasks/todos, unread emails count, health streak (water/exercise/mood), and bills due soon. "
            + "Use when the user says 'morning briefing', 'brief me', 'what do I need to know today', 'daily summary', "
            + "'start my day', 'good morning what's happening'. Speaks the briefing aloud automatically.")
    public String generateDailyBriefing(
            @ToolParam(description = "Location for weather forecast, e.g. 'Manila' or 'New York'. Use '-' to skip weather.") String weatherLocation) {

        notifier.notify("Preparing daily briefing...");
        StringBuilder briefing = new StringBuilder();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
        briefing.append("=== Daily Briefing — ").append(today).append(" ===\n\n");

        // Weather
        if (weatherLocation != null && !weatherLocation.equals("-") && !weatherLocation.isBlank()) {
            notifier.notify("__vision__Getting weather for " + weatherLocation + "...");
            try {
                String weather = weatherTools.getWeather(weatherLocation);
                briefing.append("WEATHER:\n").append(weather).append("\n\n");
            } catch (Exception e) {
                briefing.append("WEATHER: Could not fetch — ").append(e.getMessage()).append("\n\n");
            }
        }

        // Calendar
        notifier.notify("__vision__Checking calendar...");
        try {
            String events = calendarTools.getTodayEvents();
            briefing.append("CALENDAR:\n").append(events).append("\n\n");
        } catch (Exception e) {
            briefing.append("CALENDAR: ").append(e.getMessage()).append("\n\n");
        }

        // Emails
        notifier.notify("__vision__Checking emails...");
        try {
            String unread = gmailApiTools.getUnreadCount();
            briefing.append("EMAIL: ").append(unread).append("\n\n");
        } catch (Exception e) {
            briefing.append("EMAIL: ").append(e.getMessage()).append("\n\n");
        }

        // Tasks/Todos
        notifier.notify("__vision__Checking tasks...");
        try {
            String tasks = todoListTools.getPendingTasks();
            if (tasks != null && !tasks.isBlank() && !tasks.contains("No tasks")) {
                briefing.append("TASKS:\n").append(tasks).append("\n\n");
            } else {
                briefing.append("TASKS: No pending tasks.\n\n");
            }
        } catch (Exception e) {
            briefing.append("TASKS: ").append(e.getMessage()).append("\n\n");
        }

        // Health streak
        notifier.notify("__vision__Checking health streak...");
        try {
            String health = healthTrackerTools.getHealthSummary(LocalDate.now().toString());
            if (health != null && !health.isBlank() && !health.contains("No data")) {
                briefing.append("HEALTH:\n").append(health).append("\n\n");
            }
        } catch (Exception e) {
            // Health tracking may not have data — skip silently
        }

        // Bills due soon
        notifier.notify("__vision__Checking upcoming bills...");
        try {
            String bills = financeTrackerTools.getUpcomingBills(7);
            if (bills != null && !bills.isBlank() && !bills.contains("No bills")) {
                briefing.append("BILLS DUE (next 7 days):\n").append(bills).append("\n\n");
            }
        } catch (Exception e) {
            // Finance tracking may not have data — skip silently
        }

        // Budget status
        try {
            String budget = financeTrackerTools.getBudgetStatus();
            if (budget != null && !budget.isBlank() && !budget.contains("No budgets")) {
                briefing.append("BUDGET STATUS:\n").append(budget).append("\n\n");
            }
        } catch (Exception e) {
            // skip
        }

        String result = briefing.toString().trim();

        // Speak it aloud
        try {
            // Create a concise spoken version
            String spoken = buildSpokenBriefing(result);
            ttsTools.speak(spoken);
        } catch (Exception e) {
            log.warn("[Intelligence] TTS failed: {}", e.getMessage());
        }

        log.info("[Intelligence] Daily briefing generated: {} chars", result.length());
        return result;
    }

    private String buildSpokenBriefing(String fullBriefing) {
        // Extract key points for speech (keep under 500 chars for TTS)
        StringBuilder spoken = new StringBuilder("Good morning. Here's your briefing. ");
        String[] sections = fullBriefing.split("\n\n");
        for (String section : sections) {
            if (section.startsWith("WEATHER:")) {
                // Extract just the temperature and conditions
                String[] lines = section.split("\n");
                if (lines.length > 1) spoken.append(lines[1].trim()).append(". ");
            } else if (section.startsWith("CALENDAR:")) {
                long eventCount = section.lines().filter(l -> l.matches("^\\d+\\..*")).count();
                spoken.append("You have ").append(eventCount).append(" events today. ");
            } else if (section.startsWith("EMAIL:")) {
                spoken.append(section.replace("EMAIL: ", "")).append(". ");
            } else if (section.startsWith("BILLS DUE")) {
                long billCount = section.lines().filter(l -> l.trim().startsWith("-")).count();
                if (billCount > 0) spoken.append(billCount).append(" bills due this week. ");
            }
            if (spoken.length() > 400) break;
        }
        return spoken.toString().trim();
    }

    // ═══ 2. Decision Helper ═══

    @Tool(description = "Help the user make a purchase decision by analyzing it against their budget, financial goals, "
            + "and past spending patterns. Use when the user asks 'should I buy this?', 'can I afford this?', "
            + "'is this a good purchase?', 'should I spend $X on Y?'. Returns a recommendation with reasoning.")
    public String analyzeDecision(
            @ToolParam(description = "What the user wants to buy, e.g. 'new laptop', 'PS5', 'gym membership'") String item,
            @ToolParam(description = "Price in dollars, e.g. 999.99") double price,
            @ToolParam(description = "Category this falls under, e.g. 'electronics', 'entertainment', 'fitness', 'dining'") String category) {

        notifier.notify("Analyzing purchase decision...");
        StringBuilder analysis = new StringBuilder();
        analysis.append("=== Purchase Analysis: ").append(item).append(" ($")
                .append(String.format("%.2f", price)).append(") ===\n\n");

        // Check budget for this category
        String budgetStatus = null;
        try {
            budgetStatus = financeTrackerTools.getBudgetStatus();
        } catch (Exception ignored) {}

        if (budgetStatus != null && !budgetStatus.isBlank()) {
            analysis.append("BUDGET STATUS:\n").append(budgetStatus).append("\n\n");

            // Parse budget to find this category
            boolean overBudget = false;
            for (String line : budgetStatus.split("\n")) {
                String lower = line.toLowerCase();
                if (lower.contains(category.toLowerCase())) {
                    if (lower.contains("over") || lower.contains("exceeded")) {
                        overBudget = true;
                    }
                    analysis.append("Category match: ").append(line.trim()).append("\n");
                }
            }

            if (overBudget) {
                analysis.append("\n⚠ WARNING: You are already over budget in the '").append(category)
                        .append("' category. This purchase would increase the overage by $")
                        .append(String.format("%.2f", price)).append(".\n");
            }
        }

        // Check financial goals
        String goals = null;
        try {
            goals = financeTrackerTools.getFinancialGoals();
        } catch (Exception ignored) {}

        if (goals != null && !goals.isBlank() && !goals.contains("No goals")) {
            analysis.append("\nFINANCIAL GOALS:\n").append(goals).append("\n");

            // Check if this purchase conflicts with savings goals
            analysis.append("\nIMPACT: This $").append(String.format("%.2f", price))
                    .append(" purchase would reduce your savings capacity this month.\n");
        }

        // Check recent spending in this category
        String recentSpending = null;
        try {
            String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            recentSpending = financeTrackerTools.getExpensesByCategory(category, month);
        } catch (Exception ignored) {}

        if (recentSpending != null && !recentSpending.isBlank()) {
            analysis.append("\nRECENT SPENDING (").append(category).append(" this month):\n")
                    .append(recentSpending).append("\n");
        }

        // Bills due soon
        try {
            String bills = financeTrackerTools.getUpcomingBills(14);
            if (bills != null && !bills.isBlank() && !bills.contains("No bills")) {
                analysis.append("\nUPCOMING BILLS (next 14 days):\n").append(bills).append("\n");
            }
        } catch (Exception ignored) {}

        // Recommendation
        analysis.append("\nRECOMMENDATION:\n");
        if (price > 500) {
            analysis.append("This is a significant purchase ($").append(String.format("%.2f", price))
                    .append("). Consider:\n");
            analysis.append("- Is this a need or a want?\n");
            analysis.append("- Have you compared prices? (I can search the web for alternatives)\n");
            analysis.append("- Can it wait until next month if budget is tight?\n");
        } else if (price > 100) {
            analysis.append("Moderate purchase. Check if it fits within your ").append(category)
                    .append(" budget for the month.\n");
        } else {
            analysis.append("Small purchase — likely fine if budget allows.\n");
        }

        return analysis.toString().trim();
    }

    // ═══ 3. Conflict Detector ═══

    @Tool(description = "Scan your calendar for scheduling conflicts — overlapping events, back-to-back meetings "
            + "with no break, or events that conflict with known commitments. "
            + "Use when the user asks 'any conflicts in my calendar?', 'do I have overlapping meetings?', "
            + "'check my schedule for conflicts', or when planning a new event. "
            + "Can check a specific date or the next N days.")
    public String detectConflicts(
            @ToolParam(description = "Number of days ahead to scan for conflicts (1-14, default 7)") double daysAhead) {

        int days = Math.max(1, Math.min(14, (int) Math.round(daysAhead)));
        notifier.notify("Scanning calendar for conflicts (next " + days + " days)...");

        String eventsRaw;
        try {
            eventsRaw = calendarTools.getUpcomingEvents(days);
        } catch (Exception e) {
            return "Could not fetch calendar events: " + e.getMessage();
        }

        if (eventsRaw == null || eventsRaw.contains("No events")) {
            return "No events in the next " + days + " days — no conflicts possible.";
        }

        // Parse events into structured data for conflict detection
        StringBuilder result = new StringBuilder();
        result.append("=== Calendar Conflict Scan (next ").append(days).append(" days) ===\n\n");

        String[] lines = eventsRaw.split("\n");
        java.util.List<EventSlot> events = new java.util.ArrayList<>();
        String currentDate = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("---") && line.endsWith("---")) {
                currentDate = line.replace("---", "").trim();
                // Extract just the date part
                if (currentDate.contains("(")) {
                    currentDate = currentDate.substring(0, currentDate.indexOf("(")).trim();
                }
                continue;
            }
            // Parse event lines like "  9:00 AM — Meeting Title"
            if (line.contains(" — ") && !line.startsWith("Total:") && !line.startsWith("Upcoming")) {
                String[] parts = line.split(" — ", 2);
                if (parts.length == 2) {
                    String time = parts[0].trim();
                    String title = parts[1].trim();
                    events.add(new EventSlot(currentDate, time, title));
                }
            }
        }

        if (events.isEmpty()) {
            return "Could not parse calendar events for conflict detection. Raw events:\n" + eventsRaw;
        }

        // Detect conflicts
        int conflictCount = 0;
        int backToBackCount = 0;

        for (int i = 0; i < events.size(); i++) {
            EventSlot a = events.get(i);
            for (int j = i + 1; j < events.size(); j++) {
                EventSlot b = events.get(j);
                if (!a.date.equals(b.date)) continue;

                // Same time = conflict
                if (a.time.equals(b.time) && !a.time.equals("All day")) {
                    conflictCount++;
                    result.append("⚠ CONFLICT on ").append(a.date).append(":\n");
                    result.append("  ").append(a.time).append(" — ").append(a.title).append("\n");
                    result.append("  ").append(b.time).append(" — ").append(b.title).append("\n\n");
                }
            }

            // Check back-to-back (consecutive events on same day)
            if (i + 1 < events.size()) {
                EventSlot next = events.get(i + 1);
                if (a.date.equals(next.date) && !a.time.equals("All day") && !next.time.equals("All day")) {
                    // Check if they're within 15 minutes of each other
                    int aMin = parseTimeToMinutes(a.time);
                    int bMin = parseTimeToMinutes(next.time);
                    if (aMin >= 0 && bMin >= 0 && bMin - aMin > 0 && bMin - aMin <= 30) {
                        backToBackCount++;
                        result.append("⏱ BACK-TO-BACK on ").append(a.date).append(":\n");
                        result.append("  ").append(a.time).append(" — ").append(a.title).append("\n");
                        result.append("  ").append(next.time).append(" — ").append(next.title).append("\n");
                        result.append("  (only ").append(bMin - aMin).append(" min gap — consider adding buffer)\n\n");
                    }
                }
            }
        }

        if (conflictCount == 0 && backToBackCount == 0) {
            result.append("✓ No conflicts or back-to-back issues found in ").append(events.size())
                    .append(" events over ").append(days).append(" days.\n");
        } else {
            result.append("SUMMARY: ").append(conflictCount).append(" conflict(s), ")
                    .append(backToBackCount).append(" back-to-back event(s).\n");
        }

        return result.toString().trim();
    }

    private int parseTimeToMinutes(String time) {
        // Parse "9:00 AM", "2:30 PM" etc. to minutes since midnight
        try {
            time = time.trim().toUpperCase();
            boolean pm = time.contains("PM");
            time = time.replace("AM", "").replace("PM", "").trim();
            String[] parts = time.split(":");
            int hours = Integer.parseInt(parts[0].trim());
            int minutes = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (pm && hours != 12) hours += 12;
            if (!pm && hours == 12) hours = 0;
            return hours * 60 + minutes;
        } catch (Exception e) {
            return -1;
        }
    }

    // ═══ 4. Travel Planner ═══

    @Tool(description = "Create a comprehensive travel plan. Searches for flights and hotels, gets weather at destination, "
            + "calculates budget, and provides a checklist of things to prepare. "
            + "Use when the user says 'plan a trip to X', 'help me plan my vacation', 'I want to travel to X next month', "
            + "'plan my trip'. Returns a full travel brief with flights, hotels, weather, and preparation checklist.")
    public String planTrip(
            @ToolParam(description = "Origin city, e.g. 'Manila'") String from,
            @ToolParam(description = "Destination city, e.g. 'Tokyo'") String destination,
            @ToolParam(description = "Departure date YYYY-MM-DD") String departDate,
            @ToolParam(description = "Return date YYYY-MM-DD") String returnDate,
            @ToolParam(description = "Number of travelers") double travelers,
            @ToolParam(description = "Budget per person in USD (0 for no limit)") double budgetPerPerson) {

        int pax = Math.max(1, (int) Math.round(travelers));
        notifier.notify("Planning trip: " + from + " → " + destination + "...");

        StringBuilder plan = new StringBuilder();
        plan.append("=== Travel Plan: ").append(from).append(" → ").append(destination).append(" ===\n");
        plan.append("Dates: ").append(departDate).append(" to ").append(returnDate).append("\n");
        plan.append("Travelers: ").append(pax).append("\n");
        if (budgetPerPerson > 0) {
            plan.append("Budget: $").append(String.format("%.0f", budgetPerPerson)).append("/person ($")
                    .append(String.format("%.0f", budgetPerPerson * pax)).append(" total)\n");
        }
        plan.append("\n");

        // 1. Flights
        notifier.notify("__vision__Searching flights...");
        try {
            String flights = travelSearchTools.searchFlights(from, destination, departDate, returnDate, pax);
            plan.append("FLIGHTS:\n").append(flights).append("\n\n");
        } catch (Exception e) {
            plan.append("FLIGHTS: Could not search — ").append(e.getMessage()).append("\n\n");
        }

        // 2. Hotels
        notifier.notify("__vision__Searching hotels...");
        try {
            int rooms = Math.max(1, (pax + 1) / 2); // rough estimate
            String hotels = travelSearchTools.searchHotels(destination, departDate, returnDate, rooms);
            plan.append("HOTELS:\n").append(hotels).append("\n\n");
        } catch (Exception e) {
            plan.append("HOTELS: Could not search — ").append(e.getMessage()).append("\n\n");
        }

        // 3. Weather at destination
        notifier.notify("__vision__Checking destination weather...");
        try {
            String weather = weatherTools.getWeather(destination);
            plan.append("WEATHER AT DESTINATION (current):\n").append(weather).append("\n\n");
        } catch (Exception e) {
            plan.append("WEATHER: Could not fetch — ").append(e.getMessage()).append("\n\n");
        }

        // 4. Calendar conflict check
        notifier.notify("__vision__Checking calendar conflicts...");
        try {
            long daysAway = java.time.temporal.ChronoUnit.DAYS.between(
                    LocalDate.now(), LocalDate.parse(departDate));
            if (daysAway > 0 && daysAway <= 60) {
                long tripDays = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.parse(departDate), LocalDate.parse(returnDate));
                String events = calendarTools.getUpcomingEvents(Math.min(14, (int)(daysAway + tripDays)));
                if (events != null && !events.contains("No events")) {
                    // Check if any events fall during the trip
                    boolean hasConflict = false;
                    LocalDate dep = LocalDate.parse(departDate);
                    LocalDate ret = LocalDate.parse(returnDate);
                    for (String line : events.split("\n")) {
                        if (line.startsWith("---")) {
                            String dateStr = line.replace("---", "").trim();
                            if (dateStr.contains("(")) dateStr = dateStr.substring(0, dateStr.indexOf("(")).trim();
                            try {
                                LocalDate eventDate = LocalDate.parse(dateStr.trim());
                                if (!eventDate.isBefore(dep) && !eventDate.isAfter(ret)) {
                                    if (!hasConflict) {
                                        plan.append("⚠ CALENDAR CONFLICTS DURING TRIP:\n");
                                        hasConflict = true;
                                    }
                                }
                            } catch (Exception ignored) {}
                        } else if (line.contains("—") && !line.isBlank()) {
                            // This is an event line following a conflicting date
                            plan.append("  ").append(line.trim()).append("\n");
                        }
                    }
                    if (hasConflict) plan.append("\n");
                }
            }
        } catch (Exception ignored) {}

        // 5. Preparation checklist
        plan.append("PREPARATION CHECKLIST:\n");
        plan.append("  ☐ Passport — check expiry (must be valid 6+ months after return)\n");
        plan.append("  ☐ Visa — check if required for ").append(destination).append("\n");
        plan.append("  ☐ Travel insurance\n");
        plan.append("  ☐ Book flights\n");
        plan.append("  ☐ Book accommodation\n");
        plan.append("  ☐ Currency exchange / travel card\n");
        plan.append("  ☐ Phone plan — check international roaming or buy local SIM\n");
        plan.append("  ☐ Pack essentials — chargers, adapters, medications\n");
        plan.append("  ☐ Notify bank of travel dates\n");
        plan.append("  ☐ Download offline maps for ").append(destination).append("\n");

        // Budget estimate
        if (budgetPerPerson > 0) {
            plan.append("\nBUDGET ESTIMATE:\n");
            plan.append("  Budget per person: $").append(String.format("%.0f", budgetPerPerson)).append("\n");
            plan.append("  Total budget (").append(pax).append(" pax): $")
                    .append(String.format("%.0f", budgetPerPerson * pax)).append("\n");
            plan.append("  Tip: Ask me to search for specific flight/hotel prices to compare against budget.\n");
        }

        String result = plan.toString().trim();
        log.info("[Intelligence] Travel plan generated: {} → {} ({} chars)", from, destination, result.length());
        return result;
    }

    // ═══ Helper ═══

    private record EventSlot(String date, String time, String title) {}
}
