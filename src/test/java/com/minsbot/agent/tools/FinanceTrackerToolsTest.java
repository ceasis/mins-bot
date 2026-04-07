package com.minsbot.agent.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class FinanceTrackerToolsTest {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    @TempDir
    Path tempDir;

    private FinanceTrackerTools tools;

    @BeforeEach
    void setUp() throws Exception {
        ToolExecutionNotifier notifier = new ToolExecutionNotifier();
        tools = new FinanceTrackerTools(notifier);

        // Redirect FINANCE_DIR to temp directory using Unsafe (static final in Java 17)
        TestReflectionUtil.setStaticField(FinanceTrackerTools.class,
                "FINANCE_DIR", tempDir.resolve("finance"));
    }

    @Test
    void testLogExpense() {
        String result = tools.logExpense(50.00, "groceries", "Weekly grocery run");
        assertThat(result).contains("Logged expense: $50.00 on groceries");
        assertThat(result).contains("Weekly grocery run");

        String month = YearMonth.now().format(MONTH_FMT);
        Path expenseFile = tempDir.resolve("finance").resolve("expenses_" + month + ".txt");
        assertThat(expenseFile).exists();
    }

    @Test
    void testLogIncome() {
        String result = tools.logIncome(3000.00, "salary", "Monthly salary from ACME Corp");
        assertThat(result).contains("Logged income: $3000.00 from salary");
        assertThat(result).contains("Monthly salary from ACME Corp");

        String month = YearMonth.now().format(MONTH_FMT);
        Path incomeFile = tempDir.resolve("finance").resolve("income_" + month + ".txt");
        assertThat(incomeFile).exists();
    }

    @Test
    void testSetBudget() {
        String result = tools.setBudget("groceries", 500.00);
        assertThat(result).contains("Budget set: groceries = $500.00/month");

        Path budgetFile = tempDir.resolve("finance").resolve("budgets.txt");
        assertThat(budgetFile).exists();
    }

    @Test
    void testSetBudgetReplaces() {
        tools.setBudget("groceries", 400.00);
        tools.setBudget("groceries", 500.00);

        String status = tools.getBudgetStatus();
        assertThat(status).contains("500.00");
        assertThat(status).doesNotContain("400.00");
    }

    @Test
    void testGetBudgetStatus() {
        tools.setBudget("groceries", 500.00);
        tools.setBudget("dining", 200.00);
        tools.logExpense(150.00, "groceries", "Costco trip");
        tools.logExpense(75.00, "dining", "Restaurant dinner");

        String status = tools.getBudgetStatus();
        assertThat(status).contains("Budget Status");
        assertThat(status).contains("Groceries:");
        assertThat(status).contains("Budget: $500.00");
        assertThat(status).contains("Spent:  $150.00");
        assertThat(status).contains("Remaining: $350.00 [OK]");
        assertThat(status).contains("Dining:");
    }

    @Test
    void testGetBudgetStatusOverBudget() {
        tools.setBudget("dining", 100.00);
        tools.logExpense(60.00, "dining", "Lunch");
        tools.logExpense(70.00, "dining", "Dinner");

        String status = tools.getBudgetStatus();
        assertThat(status).contains("OVER");
        assertThat(status).contains("Over by: $30.00");
    }

    @Test
    void testGetBudgetStatusEmpty() {
        String status = tools.getBudgetStatus();
        assertThat(status).contains("No budgets set yet");
    }

    @Test
    void testGetMonthlyReport() {
        String month = YearMonth.now().format(MONTH_FMT);

        tools.logIncome(3000.00, "salary", "Monthly salary");
        tools.logExpense(150.00, "groceries", "Food");
        tools.logExpense(75.00, "dining", "Restaurant");

        String report = tools.getMonthlyReport(month);
        assertThat(report).contains("Monthly Report: " + month);
        assertThat(report).contains("INCOME");
        assertThat(report).contains("Total Income: $3000.00");
        assertThat(report).contains("EXPENSES");
        assertThat(report).contains("Groceries: $150.00");
        assertThat(report).contains("Dining: $75.00");
        assertThat(report).contains("Total Expenses: $225.00");
        assertThat(report).contains("NET: $2775.00");
        assertThat(report).contains("surplus");
    }

    @Test
    void testGetMonthlyReportNoData() {
        String report = tools.getMonthlyReport("2020-01");
        assertThat(report).contains("INCOME: No entries");
        assertThat(report).contains("No expenses recorded");
    }

    @Test
    void testAddAndGetBills() {
        String addResult = tools.addBill("Netflix", 15.99, 15, "monthly");
        assertThat(addResult).contains("Bill added: Netflix $15.99 due on day 15 (monthly)");

        tools.addBill("Rent", 1200.00, 1, "monthly");

        String bills = tools.getBills();
        assertThat(bills).contains("Recurring Bills");
        assertThat(bills).contains("Netflix: $15.99");
        assertThat(bills).contains("Rent: $1200.00");
        assertThat(bills).contains("Estimated monthly total:");
    }

    @Test
    void testAddBillReplaces() {
        tools.addBill("Netflix", 12.99, 15, "monthly");
        tools.addBill("Netflix", 15.99, 15, "monthly");

        String bills = tools.getBills();
        assertThat(bills).contains("15.99");
        assertThat(bills).doesNotContain("12.99");
    }

    @Test
    void testGetBillsEmpty() {
        String bills = tools.getBills();
        assertThat(bills).contains("No bills tracked yet");
    }

    @Test
    void testGetUpcomingBills() {
        // Add a bill with due day = today's day of month (so it's due today or next month)
        int todayDay = LocalDate.now().getDayOfMonth();
        tools.addBill("Test Bill", 50.00, todayDay, "monthly");

        String upcoming = tools.getUpcomingBills(31);
        // The bill should appear since its due date is within 31 days
        assertThat(upcoming).contains("Test Bill");
        assertThat(upcoming).contains("$50.00");
    }

    @Test
    void testGetUpcomingBillsNone() {
        // No bills exist
        String upcoming = tools.getUpcomingBills(7);
        assertThat(upcoming).contains("No bills tracked yet");
    }

    @Test
    void testLogDebtAndOverview() {
        String debtResult = tools.logDebt("Credit Card", 5000.00, 18.0, 150.00);
        assertThat(debtResult).contains("Debt tracked: Credit Card $5000.00 at 18.0% interest");

        tools.logDebt("Student Loan", 25000.00, 5.5, 300.00);

        String overview = tools.getDebtOverview();
        assertThat(overview).contains("Debt Overview");
        assertThat(overview).contains("Credit Card");
        assertThat(overview).contains("Remaining: $5000.00");
        assertThat(overview).contains("18.0%");
        assertThat(overview).contains("Min payment: $150.00");
        assertThat(overview).contains("Student Loan");
        assertThat(overview).contains("Remaining: $25000.00");
        assertThat(overview).contains("Total debt: $30000.00");
        assertThat(overview).contains("Total min payments: $450.00/month");
    }

    @Test
    void testGetDebtOverviewEmpty() {
        String overview = tools.getDebtOverview();
        assertThat(overview).contains("No debts tracked");
    }

    @Test
    void testSetAndGetFinancialGoals() {
        String setResult = tools.setFinancialGoal("Vacation fund", 5000.00, "2027-12-31");
        assertThat(setResult).contains("Financial goal set: Vacation fund = $5000.00 by 2027-12-31");

        String goals = tools.getFinancialGoals();
        assertThat(goals).contains("Financial Goals");
        assertThat(goals).contains("Vacation fund");
        assertThat(goals).contains("Target:   $5000.00");
        assertThat(goals).contains("Saved:    $0.00 (0%)");
        assertThat(goals).contains("2027-12-31");
        assertThat(goals).contains("days left");
    }

    @Test
    void testGetFinancialGoalsEmpty() {
        String goals = tools.getFinancialGoals();
        assertThat(goals).contains("No financial goals set yet");
    }
}
