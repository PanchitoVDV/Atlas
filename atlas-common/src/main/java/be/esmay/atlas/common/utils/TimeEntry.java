package be.esmay.atlas.common.utils;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a time duration that can be parsed from human-readable strings
 * and converted to various formats. Supports decimal values and stacking
 * multiple time units (e.g., "1w3d2h30m").
 *
 * <p>Examples of supported formats:
 * <ul>
 *   <li>"1d" - 1 day</li>
 *   <li>"2.5h" - 2.5 hours</li>
 *   <li>"1w3d2h30m" - 1 week, 3 days, 2 hours, and 30 minutes</li>
 *   <li>"perma" or "permanent" - permanent duration</li>
 * </ul>
 *
 * @author Esmaybe
 * @since 1.0.0
 */
@Data
@AllArgsConstructor
public final class TimeEntry {

    private static final Pattern TIME_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)([a-zA-Z]+)");
    private static final long PERMANENT = -1L;

    private final long totalSeconds;

    /**
     * Creates a TimeEntry from a total number of seconds.
     *
     * @param seconds the duration in seconds
     * @return a new TimeEntry instance
     */
    public static TimeEntry fromSeconds(long seconds) {
        return new TimeEntry(seconds);
    }

    /**
     * Creates a permanent TimeEntry.
     *
     * @return a new permanent TimeEntry instance
     */
    public static TimeEntry permanent() {
        return new TimeEntry(PERMANENT);
    }

    /**
     * Checks if this TimeEntry represents a permanent duration.
     *
     * @return true if this is a permanent duration, false otherwise
     */
    public boolean isPermanent() {
        return this.totalSeconds == PERMANENT;
    }

    /**
     * Gets the total duration in seconds.
     *
     * @return the duration in seconds, or -1 for permanent durations
     */
    public long getSeconds() {
        return this.totalSeconds;
    }

    /**
     * Gets the total duration in milliseconds.
     *
     * @return the duration in milliseconds, or -1 for permanent durations
     */
    public long getMilliseconds() {
        return this.isPermanent() ? PERMANENT : this.totalSeconds * 1000;
    }

    /**
     * Calculates an expiration timestamp by adding this duration to the current time.
     *
     * @return the expiration timestamp in milliseconds since epoch, or -1 for permanent durations
     */
    public long getExpirationTime() {
        return this.isPermanent() ? PERMANENT : System.currentTimeMillis() + getMilliseconds();
    }

    /**
     * Enumeration of time units used for readable time formatting.
     * Each unit contains its name and conversion factor to seconds.
     */
    private enum ReadableTimeUnit {
        YEAR("year", 365 * 24 * 3600),
        MONTH("month", 30 * 24 * 3600),
        WEEK("week", 7 * 24 * 3600),
        DAY("day", 24 * 3600),
        HOUR("hour", 3600),
        MINUTE("minute", 60),
        SECOND("second", 1);

        private final String name;
        private final long seconds;

        /**
         * Constructs a ReadableTimeUnit with the specified name and conversion factor.
         *
         * @param name    the singular name of the time unit
         * @param seconds the number of seconds in one unit
         */
        ReadableTimeUnit(String name, long seconds) {
            this.name = name;
            this.seconds = seconds;
        }

        /**
         * Formats an amount of this time unit into a human-readable string.
         * Automatically handles pluralization.
         *
         * @param amount the number of units
         * @return formatted string (e.g., "1 day", "2 hours")
         */
        public String format(long amount) {
            return amount + " " + this.name + (amount != 1 ? "s" : "");
        }
    }

    /**
     * Returns a human-readable representation of the time duration.
     *
     * <p>Examples:
     * <ul>
     *   <li>"Permanent" for permanent durations</li>
     *   <li>"0 seconds" for zero duration</li>
     *   <li>"1 day" for 86400 seconds</li>
     *   <li>"1 day and 2 hours" for 93600 seconds</li>
     *   <li>"1 week, 2 days, and 3 hours" for complex durations</li>
     * </ul>
     *
     * @return a human-readable string representation of the duration
     */
    public String getReadableTime() {
        if (isPermanent()) return "Permanent";
        if (this.totalSeconds == 0) return "0 seconds";

        List<String> parts = new ArrayList<>();
        long remaining = this.totalSeconds;

        for (ReadableTimeUnit unit : ReadableTimeUnit.values()) {
            long amount = remaining / unit.seconds;
            if (amount > 0) {
                parts.add(unit.format(amount));
                remaining %= unit.seconds;
            }
        }

        return this.formatParts(parts);
    }

    /**
     * Formats a list of time parts into a grammatically correct string.
     * Handles proper comma and "and" placement.
     *
     * @param parts list of formatted time parts (e.g., ["1 day", "2 hours"])
     * @return formatted string with proper grammar
     */
    private String formatParts(List<String> parts) {
        return switch (parts.size()) {
            case 0 -> "0 seconds";
            case 1 -> parts.get(0);
            case 2 -> parts.get(0) + " and " + parts.get(1);
            default -> {
                String last = parts.removeLast();
                yield String.join(", ", parts) + ", and " + last;
            }
        };
    }

    /**
     * Parses a time string into a TimeEntry instance.
     *
     * <p>Supported formats:
     * <ul>
     *   <li>Simple units: "1d", "2h", "30m", "45s"</li>
     *   <li>Decimal values: "1.5h", "2.5d", "0.5w"</li>
     *   <li>Stacked units: "1w3d2h30m", "2y6mo1w"</li>
     *   <li>Permanent: "perma", "permanent", "perm"</li>
     * </ul>
     *
     * <p>Supported time units:
     * <ul>
     *   <li>y, year, years - Years (365 days)</li>
     *   <li>mo, month, months - Months (30 days)</li>
     *   <li>w, week, weeks - Weeks</li>
     *   <li>d, day, days - Days</li>
     *   <li>h, hour, hours - Hours</li>
     *   <li>m, min, minute, minutes - Minutes</li>
     *   <li>s, sec, second, seconds - Seconds</li>
     * </ul>
     *
     * @param input the time string to parse
     * @return a new TimeEntry instance representing the parsed duration
     * @throws IllegalArgumentException if the input is null, empty, or has an invalid format
     */
    public static TimeEntry parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Time input cannot be null or empty");
        }

        String normalized = input.trim().toLowerCase();

        if (normalized.equals("perma") || normalized.equals("permanent") || normalized.equals("perm")) {
            return permanent();
        }

        Matcher matcher = TIME_PATTERN.matcher(normalized);
        double totalSeconds = 0;
        boolean foundMatch = false;

        while (matcher.find()) {
            foundMatch = true;
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase();

            totalSeconds += convertToSeconds(value, unit);
        }

        if (!foundMatch) {
            throw new IllegalArgumentException("Invalid time format: " + input);
        }

        return new TimeEntry(Math.round(totalSeconds));
    }

    /**
     * Checks if a string represents a valid time entry that can be parsed.
     *
     * @param input the string to validate
     * @return true if the input can be parsed as a time entry, false otherwise
     */
    public static boolean isValidTimeEntry(String input) {
        try {
            parse(input);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Converts a numeric value and time unit string to seconds.
     *
     * @param value the numeric value
     * @param unit  the time unit string (e.g., "d", "hour", "minutes")
     * @return the equivalent number of seconds
     * @throws IllegalArgumentException if the unit is not recognized
     */
    private static double convertToSeconds(double value, String unit) {
        return switch (unit) {
            case "y", "year", "years" -> value * 365 * 24 * 3600; // Approximate
            case "mo", "month", "months" -> value * 30 * 24 * 3600; // Approximate
            case "w", "week", "weeks" -> value * 7 * 24 * 3600;
            case "d", "day", "days" -> value * 24 * 3600;
            case "h", "hour", "hours" -> value * 3600;
            case "m", "min", "minute", "minutes" -> value * 60;
            case "s", "sec", "second", "seconds" -> value;
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    /**
     * Adds another TimeEntry to this one, creating a new TimeEntry with the combined duration.
     *
     * @param other the TimeEntry to add
     * @return a new TimeEntry representing the sum of both durations
     * @throws NullPointerException if other is null
     */
    public TimeEntry add(TimeEntry other) {
        if (this.isPermanent() || other.isPermanent()) {
            return permanent();
        }

        return new TimeEntry(this.totalSeconds + other.totalSeconds);
    }

    /**
     * Subtracts another TimeEntry from this one, creating a new TimeEntry with the difference.
     * The result will never be negative; if the subtraction would result in a negative duration,
     * zero is returned instead.
     *
     * @param other the TimeEntry to subtract
     * @return a new TimeEntry representing the difference, or zero if the result would be negative
     * @throws IllegalArgumentException if trying to subtract a permanent duration from a finite one
     * @throws NullPointerException     if other is null
     */
    public TimeEntry subtract(TimeEntry other) {
        if (this.isPermanent()) {
            return permanent();
        }

        if (other.isPermanent()) {
            throw new IllegalArgumentException("Cannot subtract permanent time from finite time");
        }

        return new TimeEntry(Math.max(0, this.totalSeconds - other.totalSeconds));
    }

    /**
     * Multiplies this TimeEntry by a factor, creating a new TimeEntry with the scaled duration.
     *
     * @param factor the multiplication factor
     * @return a new TimeEntry representing the scaled duration
     */
    public TimeEntry multiply(double factor) {
        if (isPermanent()) {
            return permanent();
        }

        return new TimeEntry(Math.round(this.totalSeconds * factor));
    }

    /**
     * Returns a string representation of this TimeEntry.
     * This method delegates to {@link #getReadableTime()} to provide
     * a human-readable format.
     *
     * @return a human-readable string representation of the duration
     */
    @Override
    public String toString() {
        return this.getReadableTime();
    }
}
