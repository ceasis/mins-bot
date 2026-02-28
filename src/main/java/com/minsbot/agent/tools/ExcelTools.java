package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Excel workbook automation via PowerShell COM.
 * Provides headless operations: create, read, write, format, and manage sheets.
 * Requires Excel to be installed on the PC.
 */
@Component
public class ExcelTools {

    private final ToolExecutionNotifier notifier;
    private final SystemControlService systemControl;

    public ExcelTools(ToolExecutionNotifier notifier, SystemControlService systemControl) {
        this.notifier = notifier;
        this.systemControl = systemControl;
    }

    // ─── Create ───────────────────────────────────────────────────────────────

    @Tool(description = "Create a blank Excel workbook (.xlsx) at the given path. "
            + "Uses Excel COM automation via PowerShell. Excel must be installed on the PC. "
            + "Example: createExcelFile('C:\\\\Users\\\\user\\\\Documents\\\\report.xlsx')")
    public String createExcelFile(
            @ToolParam(description = "Full path for the new Excel file (must end with .xlsx)") String path) {
        notifier.notify("Creating Excel file " + path + "...");
        try {
            Path p = Paths.get(path).toAbsolutePath();
            if (!p.toString().toLowerCase().endsWith(".xlsx")) {
                return "FAILED: Path must end with .xlsx";
            }
            if (Files.exists(p)) {
                return "File already exists: " + p + ". Delete it first or choose a different name.";
            }
            Files.createDirectories(p.getParent());

            String safePath = p.toString().replace("'", "''");
            String ps = "$excel = New-Object -ComObject Excel.Application; "
                    + "$excel.Visible = $false; "
                    + "$excel.DisplayAlerts = $false; "
                    + "$wb = $excel.Workbooks.Add(); "
                    + "$wb.SaveAs('" + safePath + "', 51); "
                    + "$wb.Close(); "
                    + "$excel.Quit(); "
                    + "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null";
            String result = systemControl.runPowerShell(ps);

            if (Files.exists(p)) {
                return "Created blank Excel workbook: " + p;
            }
            return "Excel creation may have failed. PowerShell output: " + result;
        } catch (Exception e) {
            return "Failed to create Excel file: " + e.getMessage();
        }
    }

    // ─── Write ────────────────────────────────────────────────────────────────

    @Tool(description = "Write values to one or more cells in an Excel workbook. "
            + "Cell-value pairs are comma-separated: 'A1=Hello,B2=World,C3=123'. "
            + "The workbook must already exist. Creates the sheet if it doesn't exist.")
    public String writeExcelCells(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Sheet name, e.g. 'Sheet1'") String sheetName,
            @ToolParam(description = "Comma-separated cell=value pairs, e.g. 'A1=Name,B2=Cholo'") String cellValuePairs) {
        notifier.notify("Writing to Excel cells...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "Sheet1");

            // Parse cell=value pairs
            StringBuilder ops = new StringBuilder();
            String[] pairs = cellValuePairs.split(",");
            for (String pair : pairs) {
                String trimmed = pair.trim();
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String cell = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim().replace("'", "''");
                ops.append("$ws.Range('").append(cell).append("').Value = '").append(value).append("'; ");
            }

            if (ops.isEmpty()) return "No valid cell=value pairs found. Use format: A1=Hello,B2=World";

            String ps = excelOpen(safePath)
                    + "$ws = " + getOrCreateSheet(safeSheet) + "; "
                    + ops
                    + excelSaveClose();
            String result = systemControl.runPowerShell(ps);
            return "Written cells in " + safeSheet + ": " + cellValuePairs + ". " + cleanResult(result);
        } catch (Exception e) {
            return "Failed to write Excel cells: " + e.getMessage();
        }
    }

    // ─── Read ─────────────────────────────────────────────────────────────────

    @Tool(description = "Read the value of a single cell from an Excel workbook.")
    public String readExcelCell(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Sheet name, e.g. 'Sheet1'") String sheetName,
            @ToolParam(description = "Cell address, e.g. 'A1' or 'B5'") String cell) {
        notifier.notify("Reading Excel cell " + cell + "...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "Sheet1");
            String safeCell = safe(cell, "A1");

            String ps = excelOpen(safePath)
                    + "$ws = $wb.Sheets('" + safeSheet + "'); "
                    + "$val = $ws.Range('" + safeCell + "').Text; "
                    + "if ($val) { $val } else { '(empty)' }; "
                    + excelCloseNoSave();
            String result = systemControl.runPowerShell(ps);
            return "Cell " + cell + " = " + result.trim();
        } catch (Exception e) {
            return "Failed to read Excel cell: " + e.getMessage();
        }
    }

    @Tool(description = "Read a range of cells from an Excel workbook and return as a text table. "
            + "Example range: 'A1:C10' or 'A1:D5'.")
    public String readExcelRange(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Sheet name, e.g. 'Sheet1'") String sheetName,
            @ToolParam(description = "Cell range, e.g. 'A1:C10'") String range) {
        notifier.notify("Reading Excel range " + range + "...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "Sheet1");
            String safeRange = safe(range, "A1:A10");

            String ps = excelOpen(safePath)
                    + "$ws = $wb.Sheets('" + safeSheet + "'); "
                    + "$rng = $ws.Range('" + safeRange + "'); "
                    + "$rows = $rng.Rows.Count; $cols = $rng.Columns.Count; "
                    + "$out = ''; "
                    + "for ($r = 1; $r -le $rows; $r++) { "
                    + "  $line = ''; "
                    + "  for ($c = 1; $c -le $cols; $c++) { "
                    + "    $v = $rng.Cells($r, $c).Text; "
                    + "    if ($c -gt 1) { $line += \"`t\" }; "
                    + "    $line += $v "
                    + "  }; "
                    + "  $out += $line + \"`n\" "
                    + "}; "
                    + "$out; "
                    + excelCloseNoSave();
            String result = systemControl.runPowerShell(ps);
            return "Range " + range + " from " + sheetName + ":\n" + result.trim();
        } catch (Exception e) {
            return "Failed to read Excel range: " + e.getMessage();
        }
    }

    // ─── Sheet management ─────────────────────────────────────────────────────

    @Tool(description = "List all worksheet names in an Excel workbook.")
    public String listExcelSheets(
            @ToolParam(description = "Full path to the .xlsx file") String filePath) {
        notifier.notify("Listing Excel sheets...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String ps = excelOpen(safePath)
                    + "$names = @(); "
                    + "foreach ($s in $wb.Sheets) { $names += $s.Name }; "
                    + "$names -join ', '; "
                    + excelCloseNoSave();
            String result = systemControl.runPowerShell(ps);
            return "Sheets: " + result.trim();
        } catch (Exception e) {
            return "Failed to list sheets: " + e.getMessage();
        }
    }

    @Tool(description = "Add a new worksheet to an Excel workbook.")
    public String addExcelSheet(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Name for the new sheet") String sheetName) {
        notifier.notify("Adding sheet " + sheetName + "...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "NewSheet");

            String ps = excelOpen(safePath)
                    + "$ws = $wb.Sheets.Add(); "
                    + "$ws.Name = '" + safeSheet + "'; "
                    + excelSaveClose();
            String result = systemControl.runPowerShell(ps);
            return "Added sheet '" + sheetName + "'. " + cleanResult(result);
        } catch (Exception e) {
            return "Failed to add sheet: " + e.getMessage();
        }
    }

    @Tool(description = "Delete a worksheet from an Excel workbook.")
    public String deleteExcelSheet(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Name of the sheet to delete") String sheetName) {
        notifier.notify("Deleting sheet " + sheetName + "...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "Sheet1");

            String ps = excelOpen(safePath)
                    + "$wb.Sheets('" + safeSheet + "').Delete(); "
                    + excelSaveClose();
            String result = systemControl.runPowerShell(ps);
            return "Deleted sheet '" + sheetName + "'. " + cleanResult(result);
        } catch (Exception e) {
            return "Failed to delete sheet: " + e.getMessage();
        }
    }

    // ─── Formatting ───────────────────────────────────────────────────────────

    @Tool(description = "Format cells in an Excel workbook. Set bold, italic, font size, or font color. "
            + "Only non-empty parameters are applied. Color format: '#FF0000' for red, '#0000FF' for blue, etc.")
    public String formatExcelCells(
            @ToolParam(description = "Full path to the .xlsx file") String filePath,
            @ToolParam(description = "Sheet name, e.g. 'Sheet1'") String sheetName,
            @ToolParam(description = "Cell or range to format, e.g. 'A1' or 'A1:C1'") String range,
            @ToolParam(description = "Bold: 'true' or 'false' (or empty to skip)") String bold,
            @ToolParam(description = "Italic: 'true' or 'false' (or empty to skip)") String italic,
            @ToolParam(description = "Font size as number (e.g. '14') or empty to skip") String fontSize) {
        notifier.notify("Formatting Excel cells " + range + "...");
        try {
            Path p = validateExcelPath(filePath);
            if (p == null) return "File not found or not .xlsx: " + filePath;

            String safePath = p.toString().replace("'", "''");
            String safeSheet = safe(sheetName, "Sheet1");
            String safeRange = safe(range, "A1");

            StringBuilder fmt = new StringBuilder();
            fmt.append("$rng = $ws.Range('").append(safeRange).append("'); ");
            if ("true".equalsIgnoreCase(nullToEmpty(bold).trim())) {
                fmt.append("$rng.Font.Bold = $true; ");
            } else if ("false".equalsIgnoreCase(nullToEmpty(bold).trim())) {
                fmt.append("$rng.Font.Bold = $false; ");
            }
            if ("true".equalsIgnoreCase(nullToEmpty(italic).trim())) {
                fmt.append("$rng.Font.Italic = $true; ");
            } else if ("false".equalsIgnoreCase(nullToEmpty(italic).trim())) {
                fmt.append("$rng.Font.Italic = $false; ");
            }
            String fs = nullToEmpty(fontSize).trim();
            if (!fs.isEmpty()) {
                try {
                    int size = Integer.parseInt(fs);
                    if (size > 0 && size <= 400) {
                        fmt.append("$rng.Font.Size = ").append(size).append("; ");
                    }
                } catch (NumberFormatException ignored) {}
            }

            String ps = excelOpen(safePath)
                    + "$ws = $wb.Sheets('" + safeSheet + "'); "
                    + fmt
                    + excelSaveClose();
            String result = systemControl.runPowerShell(ps);
            return "Formatted " + range + " in " + sheetName + ". " + cleanResult(result);
        } catch (Exception e) {
            return "Failed to format cells: " + e.getMessage();
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private Path validateExcelPath(String filePath) {
        if (filePath == null) return null;
        Path p = Paths.get(filePath).toAbsolutePath();
        if (!p.toString().toLowerCase().endsWith(".xlsx")) return null;
        if (!Files.exists(p)) return null;
        return p;
    }

    private static String excelOpen(String safePath) {
        return "$excel = New-Object -ComObject Excel.Application; "
                + "$excel.Visible = $false; "
                + "$excel.DisplayAlerts = $false; "
                + "$wb = $excel.Workbooks.Open('" + safePath + "'); ";
    }

    private static String excelSaveClose() {
        return "$wb.Save(); $wb.Close(); $excel.Quit(); "
                + "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null";
    }

    private static String excelCloseNoSave() {
        return "$wb.Close($false); $excel.Quit(); "
                + "[System.Runtime.Interopservices.Marshal]::ReleaseComObject($excel) | Out-Null";
    }

    /** Get existing sheet or create it if not found. */
    private static String getOrCreateSheet(String safeSheet) {
        return "$(try { $wb.Sheets('" + safeSheet + "') } catch { "
                + "$s = $wb.Sheets.Add(); $s.Name = '" + safeSheet + "'; $s })";
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.replace("'", "''");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String cleanResult(String result) {
        if (result == null) return "";
        String trimmed = result.trim();
        if (trimmed.equals("Command completed (no output).")) return "";
        return trimmed;
    }
}
