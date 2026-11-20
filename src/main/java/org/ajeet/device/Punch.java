package org.ajeet.device;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** One punch event read from the device log. */
public record Punch(int enrollNumber, int verifyMode, LocalDateTime time) {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Sheet row shape: Employee ID | Punch Time | Verify Mode. */
    public List<Object> toRow() {
        return List.of(
                enrollNumber,
                time.format(FMT),
                verifyLabel(verifyMode));
    }

    private static String verifyLabel(int mode) {
        return switch (mode) {
            case 1 -> "Fingerprint";
            case 2 -> "Password";
            case 3 -> "Card";
            case 4 -> "Fingerprint+Card";
            case 5 -> "Fingerprint+Password";
            case 6 -> "Card+Fingerprint";
            case 7 -> "Face";
            case 11 -> "Vein";
            case 15 -> "Palm";
            default -> "Mode " + mode;
        };
    }
}
