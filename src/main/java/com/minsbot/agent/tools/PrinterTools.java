package com.minsbot.agent.tools;

import com.minsbot.agent.SystemControlService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Printing tools for listing printers and sending files to print.
 */
@Component
public class PrinterTools {

    private final SystemControlService systemControl;
    private final ToolExecutionNotifier notifier;

    public PrinterTools(SystemControlService systemControl, ToolExecutionNotifier notifier) {
        this.systemControl = systemControl;
        this.notifier = notifier;
    }

    @Tool(description = "List all printers installed on the computer with their names, drivers, and status.")
    public String listPrinters() {
        notifier.notify("Listing printers...");
        return systemControl.runPowerShell(
                "Get-Printer | Select-Object Name, DriverName, PortName, PrinterStatus | Format-Table -AutoSize");
    }

    @Tool(description = "Print a file using the default printer. Supports PDF, Word, text, and image files. "
            + "Example: printFile('C:\\\\Users\\\\user\\\\Documents\\\\report.pdf')")
    public String printFile(
            @ToolParam(description = "Full path to the file to print") String filePath) {
        notifier.notify("Printing " + filePath + "...");
        Path path = Paths.get(filePath).toAbsolutePath();
        if (!Files.exists(path)) {
            return "FAILED: File not found: " + path;
        }
        return systemControl.runPowerShell(
                "Start-Process -FilePath '" + path.toString().replace("'", "''") + "' -Verb Print");
    }

    @Tool(description = "Print a file to a specific printer by name. Use listPrinters() first to find printer names. "
            + "Example: printToSpecificPrinter('C:\\\\doc.pdf', 'HP LaserJet')")
    public String printToSpecificPrinter(
            @ToolParam(description = "Full path to the file to print") String filePath,
            @ToolParam(description = "Name of the printer to use (from listPrinters)") String printerName) {
        notifier.notify("Printing to " + printerName + "...");
        Path path = Paths.get(filePath).toAbsolutePath();
        if (!Files.exists(path)) {
            return "FAILED: File not found: " + path;
        }
        String safePrinter = printerName.replace("'", "''");
        return systemControl.runPowerShell(
                "Set-Printer -Name '" + safePrinter + "' -Default; "
                + "Start-Process -FilePath '" + path.toString().replace("'", "''") + "' -Verb Print");
    }

    @Tool(description = "Return the user's current default Windows printer (the one used when no printer is specified). Use when the user asks 'what printer am I using' or before a print that doesn't name a target.")
    public String getDefaultPrinter() {
        notifier.notify("Getting default printer...");
        return systemControl.runPowerShell(
                "Get-CimInstance -ClassName Win32_Printer | Where-Object { $_.Default -eq $true } | Select-Object -ExpandProperty Name");
    }
}
