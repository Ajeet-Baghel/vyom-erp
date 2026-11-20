package org.ajeet.sheets;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory stand-in so the app runs without Google credentials.
 * Seeds a small attendance-like table the UI can exercise.
 */
public class MemorySheetsClient implements SheetsClient {

    private final List<List<Object>> rows = new ArrayList<>(List.of(
            new ArrayList<>(List.of("Employee ID", "Name", "Date", "Status", "Hours")),
            new ArrayList<>(List.of("E001", "Asha Verma", "2026-10-28", "Present", 8)),
            new ArrayList<>(List.of("E002", "Rahul Mehta", "2026-10-28", "WFH", 7.5)),
            new ArrayList<>(List.of("E003", "Priya Nair", "2026-10-28", "Leave", 0))
    ));

    @Override
    public synchronized List<List<Object>> readAll() {
        List<List<Object>> copy = new ArrayList<>(rows.size());
        for (List<Object> r : rows) copy.add(new ArrayList<>(r));
        return copy;
    }

    @Override
    public synchronized void appendRow(List<Object> values) {
        rows.add(new ArrayList<>(values));
    }

    @Override
    public synchronized void updateRow(int rowNumber, List<Object> values) {
        check(rowNumber);
        rows.set(rowNumber - 1, new ArrayList<>(values));
    }

    @Override
    public synchronized void deleteRow(int rowNumber) {
        check(rowNumber);
        rows.remove(rowNumber - 1);
    }

    private void check(int rowNumber) {
        if (rowNumber < 2) throw new IllegalArgumentException("Cannot modify the header row");
        if (rowNumber > rows.size()) {
            throw new IllegalArgumentException("Row " + rowNumber + " does not exist");
        }
    }

    @Override
    public String describe() {
        return "in-memory demo";
    }
}
