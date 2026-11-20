package org.ajeet.sheets;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over the row store (Google Sheet or in-memory demo data).
 * Row numbering matches the sheet: row 1 is the header, data starts at row 2.
 */
public interface SheetsClient {

    /** All values including the header row (index 0 = sheet row 1). */
    List<List<Object>> readAll() throws IOException;

    /** Append a new data row at the bottom. */
    void appendRow(List<Object> values) throws IOException;

    /** Overwrite the values of an existing row. {@code rowNumber} is 1-based, >= 2. */
    void updateRow(int rowNumber, List<Object> values) throws IOException;

    /** Delete an existing row. {@code rowNumber} is 1-based, >= 2. */
    void deleteRow(int rowNumber) throws IOException;

    /** Human-readable description of the backing store, shown in the UI. */
    String describe();
}
