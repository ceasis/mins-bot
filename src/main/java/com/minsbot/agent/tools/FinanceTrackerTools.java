package com.minsbot.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Finance tracking tools backed by flat text files in ~/mins_bot_data/finance/.
 * Tracks expenses, income, budgets, bills, debts, and financial goals.
 */
@Component
public class FinanceTrackerTools {

    private static final Path FINANCE_DIR =
            Paths.get(System.getProperty("user.home"), "mins_bot_data", "finance");

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final ToolExecutionNotifier notifier;

    public FinanceTrackerTools(ToolExecutionNotifier notifier) {
        this.notifier = notifier;
    }

    // ─── Expense & income logging ──────────────────────────────────────────

    @Tool(description = "Log an expense. Appended to the current month's expense file. "
            + "Use when the user says 'I spent $50 on groceries', 'log expense: lunch $15'.")
    public String logExpense(
            @ToolParam(description = "Amount spent") double amount,
            @ToolParam(description = "Category, e.g. 'groceries', 'dining', 'transport', 'entertainment', 'utilities'") String category,
            @ToolParam(description = "Description, e.g. 'Weekly grocery run at Costco'") String description) {
        notifier.notify("Logging expense...");
        try {
            ensureDirExists();
            String month = YearMonth.now().format(MONTH_FMT);
            Path file = FINANCE_DIR.resolve("expenses_" + month + ".txt");
            String today = LocalDate.now().format(DATE_FMT);
            String entry = "[" + now() + "] " + today + " | " + String.format("%.2f", amount)
                    + " | " + category.trim() + " | " + description.trim() + "\n";
            appendToFile(file, entry);
            return "Logged expense: $" + String.format("%.2f", amount) + " on " + category + " (" + description + ").";
        } catch (IOException e) {
            return "Failed to log expense: " + e.getMessage();
        }
    }

    @Tool(description = "Log income received. Appended to the current month's income file. "
            + "Use when the user says 'I received $3000 salary', 'got paid $500 for freelance work'.")
    public String logIncome(
            @ToolParam(description = "Amount received") double amount,
            @ToolParam(description = "Source, e.g. 'salary', 'freelance', 'investment', 'refund'") String source,
            @ToolParam(description = "Description, e.g. 'Monthly salary from ACME Corp'") String description) {
        notifier.notify("Logging income...");
        try {
            ensureDirExists();
            String month = YearMonth.now().format(MONTH_FMT);
            Path file = FINANCE_DIR.resolve("income_" + month + ".txt");
            String today = LocalDate.now().format(DATE_FMT);
            String entry = "[" + now() + "] " + today + " | " + String.format("%.2f", amount)
                    + " | " + source.trim() + " | " + description.trim() + "\n";
            appendToFile(file, entry);
            return "Logged income: $" + String.format("%.2f", amount) + " from " + source + " (" + description + ").";
        } catch (IOException e) {
            return "Failed to log income: " + e.getMessage();
        }
    }

    // ─── Budgets ───────────────────────────────────────────────────────────

    @Tool(description = "Set a monthly budget for a spending category. "
            + "Use when the user says 'set budget for groceries to $500', 'my dining budget is $200/month'.")
    public String setBudget(
            @ToolParam(description = "Category, e.g. 'groceries', 'dining', 'transport', 'entertainment'") String category,
            @ToolParam(description = "Monthly spending limit in dollars") double monthlyLimit) {
        notifier.notify("Setting budget for " + category + "...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("budgets.txt");

            List<String> lines = new ArrayList<>();
            if (Files.exists(file)) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }

            String budgetLine = category.trim().toLowerCase() + " | " + String.format("%.2f", monthlyLimit);
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().startsWith(category.trim().toLowerCase() + " |")) {
                    lines.set(i, budgetLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(budgetLine);
            }

            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return "Budget set: " + category + " = $" + String.format("%.2f", monthlyLimit) + "/month.";
        } catch (IOException e) {
            return "Failed to set budget: " + e.getMessage();
        }
    }

    @Tool(description = "Show all budgets vs actual spending this month. "
            + "Use when the user asks 'how am I doing on my budgets?', 'budget status', 'am I over budget?'.")
    public String getBudgetStatus() {
        notifier.notify("Checking budget status...");
        try {
            ensureDirExists();
            Path budgetFile = FINANCE_DIR.resolve("budgets.txt");
            if (!Files.exists(budgetFile)) {
                return "No budgets set yet. Use setBudget to create one.";
            }

            List<String> budgetLines = Files.readAllLines(budgetFile, StandardCharsets.UTF_8);
            if (budgetLines.isEmpty()) {
                return "No budgets set yet.";
            }

            Map<String, Double> spending = getSpendingByCategory(YearMonth.now().format(MONTH_FMT));

            StringBuilder sb = new StringBuilder();
            sb.append("Budget Status (").append(YearMonth.now().format(MONTH_FMT)).append(")\n");
            sb.append("═══════════════════════════════\n\n");

            for (String line : budgetLines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 2) continue;
                String cat = parts[0].trim();
                double limit = parseDouble(parts[1].trim());
                double spent = spending.getOrDefault(cat.toLowerCase(), 0.0);
                double remaining = limit - spent;
                String status = remaining >= 0 ? "OK" : "OVER";

                sb.append(capitalize(cat)).append(":\n");
                sb.append("  Budget: $").append(String.format("%.2f", limit)).append("\n");
                sb.append("  Spent:  $").append(String.format("%.2f", spent)).append("\n");
                sb.append("  ").append(remaining >= 0 ? "Remaining" : "Over by").append(": $")
                        .append(String.format("%.2f", Math.abs(remaining)))
                        .append(" [").append(status).append("]\n\n");
            }

            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get budget status: " + e.getMessage();
        }
    }

    // ─── Reports ───────────────────────────────────────────────────────────

    @Tool(description = "Get a monthly financial report showing income, expenses by category, and net. "
            + "Use when the user asks 'how did I do last month?', 'monthly report for January'.")
    public String getMonthlyReport(
            @ToolParam(description = "Year-month in YYYY-MM format, e.g. '2025-01'") String yearMonth) {
        notifier.notify("Generating monthly report for " + yearMonth + "...");
        try {
            ensureDirExists();
            StringBuilder sb = new StringBuilder();
            sb.append("Monthly Report: ").append(yearMonth).append("\n");
            sb.append("═══════════════════════════════\n\n");

            // Income
            double totalIncome = 0;
            Path incomeFile = FINANCE_DIR.resolve("income_" + yearMonth + ".txt");
            if (Files.exists(incomeFile)) {
                sb.append("INCOME\n");
                for (String line : Files.readAllLines(incomeFile, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    sb.append("  ").append(line).append("\n");
                    totalIncome += extractAmount(line);
                }
                sb.append("  Total Income: $").append(String.format("%.2f", totalIncome)).append("\n\n");
            } else {
                sb.append("INCOME: No entries\n\n");
            }

            // Expenses by category
            Map<String, Double> spending = getSpendingByCategory(yearMonth);
            Map<String, List<String>> expenseDetails = getExpenseDetails(yearMonth);
            double totalExpenses = 0;

            sb.append("EXPENSES\n");
            if (spending.isEmpty()) {
                sb.append("  No expenses recorded\n\n");
            } else {
                for (Map.Entry<String, Double> entry : spending.entrySet()) {
                    sb.append("  ").append(capitalize(entry.getKey())).append(": $")
                            .append(String.format("%.2f", entry.getValue())).append("\n");
                    totalExpenses += entry.getValue();
                }
                sb.append("  Total Expenses: $").append(String.format("%.2f", totalExpenses)).append("\n\n");
            }

            // Net
            double net = totalIncome - totalExpenses;
            sb.append("NET: $").append(String.format("%.2f", net));
            sb.append(net >= 0 ? " (surplus)" : " (deficit)");

            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to generate report: " + e.getMessage();
        }
    }

    @Tool(description = "Get expenses filtered by category for a specific month. "
            + "Use when the user asks 'how much did I spend on dining in January?', 'show grocery expenses'.")
    public String getExpensesByCategory(
            @ToolParam(description = "Category to filter, e.g. 'groceries', 'dining'") String category,
            @ToolParam(description = "Year-month in YYYY-MM format, e.g. '2025-01'. Use current month if not specified") String yearMonth) {
        notifier.notify("Loading " + category + " expenses for " + yearMonth + "...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("expenses_" + yearMonth + ".txt");
            if (!Files.exists(file)) {
                return "No expenses recorded for " + yearMonth + ".";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append(capitalize(category)).append(" Expenses (").append(yearMonth).append(")\n");
            sb.append("═══════════════════════════════\n\n");

            double total = 0;
            int count = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length >= 3) {
                    String lineCat = parts[2].trim().toLowerCase();
                    if (lineCat.equals(category.trim().toLowerCase())) {
                        sb.append("  ").append(line.trim()).append("\n");
                        total += extractAmount(line);
                        count++;
                    }
                }
            }

            if (count == 0) {
                return "No " + category + " expenses found for " + yearMonth + ".";
            }

            sb.append("\nTotal: $").append(String.format("%.2f", total)).append(" (").append(count).append(" entries)");
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get expenses: " + e.getMessage();
        }
    }

    // ─── Bills ─────────────────────────────────────────────────────────────

    @Tool(description = "Add a recurring bill to track. "
            + "Use when the user says 'add my rent $1200 due on the 1st', 'track Netflix $15.99 monthly'.")
    public String addBill(
            @ToolParam(description = "Bill name, e.g. 'Rent', 'Netflix', 'Electric'") String name,
            @ToolParam(description = "Amount due") double amount,
            @ToolParam(description = "Day of month due, e.g. 1, 15, 28") int dueDay,
            @ToolParam(description = "Frequency: 'monthly', 'quarterly', 'yearly'") String frequency) {
        notifier.notify("Adding bill: " + name + "...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("bills.txt");

            List<String> lines = new ArrayList<>();
            if (Files.exists(file)) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }

            // Replace if bill name already exists
            String billLine = name.trim() + " | " + String.format("%.2f", amount) + " | " + dueDay + " | " + frequency.trim().toLowerCase();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().startsWith(name.trim().toLowerCase() + " |")) {
                    lines.set(i, billLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(billLine);
            }

            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return "Bill added: " + name + " $" + String.format("%.2f", amount)
                    + " due on day " + dueDay + " (" + frequency + ").";
        } catch (IOException e) {
            return "Failed to add bill: " + e.getMessage();
        }
    }

    @Tool(description = "List all recurring bills with their amounts and due dates. "
            + "Use when the user asks 'what bills do I have?', 'show my recurring bills'.")
    public String getBills() {
        notifier.notify("Loading bills...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("bills.txt");
            if (!Files.exists(file)) {
                return "No bills tracked yet. Use addBill to add one.";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return "No bills tracked yet.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Recurring Bills\n");
            sb.append("═══════════════════════════════\n\n");

            double totalMonthly = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 4) continue;
                String bName = parts[0].trim();
                double bAmount = parseDouble(parts[1].trim());
                int bDay = parseInt(parts[2].trim());
                String bFreq = parts[3].trim();

                String nextDue = getNextDueDate(bDay, bFreq);
                sb.append("  ").append(bName).append(": $").append(String.format("%.2f", bAmount))
                        .append(" | Due: day ").append(bDay).append(" (").append(bFreq).append(")")
                        .append(" | Next: ").append(nextDue).append("\n");

                if (bFreq.equals("monthly")) totalMonthly += bAmount;
                else if (bFreq.equals("quarterly")) totalMonthly += bAmount / 3;
                else if (bFreq.equals("yearly")) totalMonthly += bAmount / 12;
            }

            sb.append("\nEstimated monthly total: $").append(String.format("%.2f", totalMonthly));
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get bills: " + e.getMessage();
        }
    }

    @Tool(description = "Show bills due in the next N days. "
            + "Use when the user asks 'what bills are due this week?', 'upcoming bills'.")
    public String getUpcomingBills(
            @ToolParam(description = "Number of days to look ahead") int days) {
        notifier.notify("Checking bills due in next " + days + " days...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("bills.txt");
            if (!Files.exists(file)) {
                return "No bills tracked yet.";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            LocalDate today = LocalDate.now();
            LocalDate cutoff = today.plusDays(days);

            StringBuilder sb = new StringBuilder();
            sb.append("Bills Due in Next ").append(days).append(" Days\n");
            sb.append("═══════════════════════════════\n\n");

            int count = 0;
            double total = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 4) continue;
                String bName = parts[0].trim();
                double bAmount = parseDouble(parts[1].trim());
                int bDay = parseInt(parts[2].trim());

                LocalDate nextDue = getNextDueDateAsLocal(bDay);
                if (!nextDue.isAfter(cutoff) && !nextDue.isBefore(today)) {
                    sb.append("  ").append(nextDue.format(DATE_FMT)).append(" | ").append(bName)
                            .append(": $").append(String.format("%.2f", bAmount)).append("\n");
                    total += bAmount;
                    count++;
                }
            }

            if (count == 0) {
                return "No bills due in the next " + days + " days.";
            }

            sb.append("\nTotal due: $").append(String.format("%.2f", total)).append(" (").append(count).append(" bills)");
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to check upcoming bills: " + e.getMessage();
        }
    }

    // ─── Debts ─────────────────────────────────────────────────────────────

    @Tool(description = "Track a debt (loan, credit card, etc.). "
            + "Use when the user says 'I owe $5000 on my credit card at 18% interest'.")
    public String logDebt(
            @ToolParam(description = "Debt name, e.g. 'Credit Card', 'Student Loan', 'Car Loan'") String name,
            @ToolParam(description = "Total remaining amount owed") double totalAmount,
            @ToolParam(description = "Annual interest rate as percentage, e.g. 18.0 for 18%") double interestRate,
            @ToolParam(description = "Minimum monthly payment") double minimumPayment) {
        notifier.notify("Tracking debt: " + name + "...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("debts.txt");

            List<String> lines = new ArrayList<>();
            if (Files.exists(file)) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }

            String today = LocalDate.now().format(DATE_FMT);
            String debtLine = name.trim() + " | " + String.format("%.2f", totalAmount)
                    + " | " + String.format("%.1f", interestRate) + "%"
                    + " | " + String.format("%.2f", minimumPayment)
                    + " | " + today;

            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().startsWith(name.trim().toLowerCase() + " |")) {
                    lines.set(i, debtLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(debtLine);
            }

            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return "Debt tracked: " + name + " $" + String.format("%.2f", totalAmount)
                    + " at " + String.format("%.1f", interestRate) + "% interest, min payment $"
                    + String.format("%.2f", minimumPayment) + ".";
        } catch (IOException e) {
            return "Failed to log debt: " + e.getMessage();
        }
    }

    @Tool(description = "Get an overview of all tracked debts with remaining amounts and interest. "
            + "Use when the user asks 'how much do I owe?', 'debt overview', 'show my debts'.")
    public String getDebtOverview() {
        notifier.notify("Loading debt overview...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("debts.txt");
            if (!Files.exists(file)) {
                return "No debts tracked. Use logDebt to add one.";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return "No debts tracked.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Debt Overview\n");
            sb.append("═══════════════════════════════\n\n");

            double totalDebt = 0;
            double totalMinPayment = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 4) continue;
                String dName = parts[0].trim();
                double dAmount = parseDouble(parts[1].trim());
                String dRate = parts[2].trim();
                double dMin = parseDouble(parts[3].trim());
                String dDate = parts.length >= 5 ? parts[4].trim() : "-";

                sb.append("  ").append(dName).append("\n");
                sb.append("    Remaining: $").append(String.format("%.2f", dAmount)).append("\n");
                sb.append("    Interest:  ").append(dRate).append("\n");
                sb.append("    Min payment: $").append(String.format("%.2f", dMin)).append("/month\n");
                sb.append("    Last updated: ").append(dDate).append("\n\n");

                totalDebt += dAmount;
                totalMinPayment += dMin;
            }

            sb.append("Total debt: $").append(String.format("%.2f", totalDebt)).append("\n");
            sb.append("Total min payments: $").append(String.format("%.2f", totalMinPayment)).append("/month");
            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get debt overview: " + e.getMessage();
        }
    }

    // ─── Financial goals ───────────────────────────────────────────────────

    @Tool(description = "Set a financial/savings goal with a target amount and deadline. "
            + "Use when the user says 'I want to save $5000 for a vacation by December'.")
    public String setFinancialGoal(
            @ToolParam(description = "Goal name, e.g. 'Vacation fund', 'Emergency fund', 'New car'") String name,
            @ToolParam(description = "Target amount in dollars") double targetAmount,
            @ToolParam(description = "Deadline in YYYY-MM-DD format, e.g. '2025-12-31'") String deadline) {
        notifier.notify("Setting financial goal: " + name + "...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("goals.txt");

            List<String> lines = new ArrayList<>();
            if (Files.exists(file)) {
                lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
            }

            String today = LocalDate.now().format(DATE_FMT);
            String goalLine = name.trim() + " | " + String.format("%.2f", targetAmount)
                    + " | 0.00"
                    + " | " + deadline.trim()
                    + " | " + today;

            // Check if goal exists and preserve saved amount
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase().startsWith(name.trim().toLowerCase() + " |")) {
                    // Preserve the saved amount from existing entry
                    String[] existingParts = lines.get(i).split("\\|");
                    String savedAmount = existingParts.length >= 3 ? existingParts[2].trim() : "0.00";
                    goalLine = name.trim() + " | " + String.format("%.2f", targetAmount)
                            + " | " + savedAmount
                            + " | " + deadline.trim()
                            + " | " + today;
                    lines.set(i, goalLine);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                lines.add(goalLine);
            }

            Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
            return "Financial goal set: " + name + " = $" + String.format("%.2f", targetAmount)
                    + " by " + deadline + ".";
        } catch (IOException e) {
            return "Failed to set goal: " + e.getMessage();
        }
    }

    @Tool(description = "List all financial goals with progress toward each. "
            + "Use when the user asks 'how are my savings goals?', 'show financial goals'.")
    public String getFinancialGoals() {
        notifier.notify("Loading financial goals...");
        try {
            ensureDirExists();
            Path file = FINANCE_DIR.resolve("goals.txt");
            if (!Files.exists(file)) {
                return "No financial goals set yet. Use setFinancialGoal to create one.";
            }

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return "No financial goals set yet.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Financial Goals\n");
            sb.append("═══════════════════════════════\n\n");

            for (String line : lines) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 4) continue;
                String gName = parts[0].trim();
                double gTarget = parseDouble(parts[1].trim());
                double gSaved = parts.length >= 3 ? parseDouble(parts[2].trim()) : 0;
                String gDeadline = parts[3].trim();

                double pct = gTarget > 0 ? (gSaved / gTarget) * 100 : 0;
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(
                        LocalDate.now(), LocalDate.parse(gDeadline, DATE_FMT));

                sb.append("  ").append(gName).append("\n");
                sb.append("    Target:   $").append(String.format("%.2f", gTarget)).append("\n");
                sb.append("    Saved:    $").append(String.format("%.2f", gSaved))
                        .append(" (").append(String.format("%.0f", pct)).append("%)\n");
                sb.append("    Deadline: ").append(gDeadline);
                if (daysLeft > 0) {
                    sb.append(" (").append(daysLeft).append(" days left)");
                } else if (daysLeft == 0) {
                    sb.append(" (TODAY)");
                } else {
                    sb.append(" (PAST DUE by ").append(Math.abs(daysLeft)).append(" days)");
                }
                sb.append("\n\n");
            }

            return sb.toString().trim();
        } catch (IOException e) {
            return "Failed to get goals: " + e.getMessage();
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private void ensureDirExists() throws IOException {
        if (!Files.exists(FINANCE_DIR)) {
            Files.createDirectories(FINANCE_DIR);
        }
    }

    private void appendToFile(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String now() {
        return LocalDateTime.now().format(TIME_FMT);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.replaceAll("[^\\d.\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private int parseInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^\\d\\-]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double extractAmount(String line) {
        // Lines: [HH:mm] YYYY-MM-DD | 50.00 | category | desc
        try {
            String[] parts = line.split("\\|");
            if (parts.length >= 2) {
                return parseDouble(parts[1].trim());
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private Map<String, Double> getSpendingByCategory(String yearMonth) throws IOException {
        Map<String, Double> spending = new LinkedHashMap<>();
        Path file = FINANCE_DIR.resolve("expenses_" + yearMonth + ".txt");
        if (!Files.exists(file)) return spending;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 3) {
                String cat = parts[2].trim().toLowerCase();
                double amount = parseDouble(parts[1].trim());
                spending.merge(cat, amount, Double::sum);
            }
        }
        return spending;
    }

    private Map<String, List<String>> getExpenseDetails(String yearMonth) throws IOException {
        Map<String, List<String>> details = new LinkedHashMap<>();
        Path file = FINANCE_DIR.resolve("expenses_" + yearMonth + ".txt");
        if (!Files.exists(file)) return details;

        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\|");
            if (parts.length >= 3) {
                String cat = parts[2].trim().toLowerCase();
                details.computeIfAbsent(cat, k -> new ArrayList<>()).add(line.trim());
            }
        }
        return details;
    }

    private String getNextDueDate(int dueDay, String frequency) {
        LocalDate nextDue = getNextDueDateAsLocal(dueDay);
        return nextDue.format(DATE_FMT);
    }

    private LocalDate getNextDueDateAsLocal(int dueDay) {
        LocalDate today = LocalDate.now();
        int safeDay = Math.min(dueDay, today.lengthOfMonth());
        LocalDate thisMonth = today.withDayOfMonth(safeDay);
        if (!thisMonth.isBefore(today)) {
            return thisMonth;
        }
        // Next month
        LocalDate nextMonth = today.plusMonths(1);
        safeDay = Math.min(dueDay, nextMonth.lengthOfMonth());
        return nextMonth.withDayOfMonth(safeDay);
    }
}
