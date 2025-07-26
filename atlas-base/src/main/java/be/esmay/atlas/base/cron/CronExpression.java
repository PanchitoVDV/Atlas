package be.esmay.atlas.base.cron;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.StringTokenizer;

public final class CronExpression {

    private final String expression;
    private final int[] minutes = new int[60];
    private final int[] hours = new int[24];
    private final int[] daysOfMonth = new int[32];
    private final int[] months = new int[13];
    private final int[] daysOfWeek = new int[8];

    public CronExpression(String expression) {
        this.expression = expression;
        this.parseExpression();
    }

    private void parseExpression() {
        StringTokenizer tokenizer = new StringTokenizer(this.expression);
        
        if (tokenizer.countTokens() != 5) {
            throw new IllegalArgumentException("Invalid cron expression: " + this.expression);
        }

        this.parseField(tokenizer.nextToken(), this.minutes, 0, 59);
        this.parseField(tokenizer.nextToken(), this.hours, 0, 23);
        this.parseField(tokenizer.nextToken(), this.daysOfMonth, 1, 31);
        this.parseField(tokenizer.nextToken(), this.months, 1, 12);
        this.parseField(tokenizer.nextToken(), this.daysOfWeek, 0, 7);
    }

    private void parseField(String field, int[] targetArray, int min, int max) {
        if (field.equals("*")) {
            for (int i = min; i <= max; i++) {
                targetArray[i] = 1;
            }
            return;
        }

        if (field.contains("/")) {
            String[] parts = field.split("/");
            String range = parts[0];
            int step = Integer.parseInt(parts[1]);
            
            if (range.equals("*")) {
                for (int i = min; i <= max; i += step) {
                    targetArray[i] = 1;
                }
            } else {
                String[] rangeParts = range.split("-");
                int start = Integer.parseInt(rangeParts[0]);
                int end = rangeParts.length > 1 ? Integer.parseInt(rangeParts[1]) : max;
                
                for (int i = start; i <= end; i += step) {
                    if (i >= min && i <= max) {
                        targetArray[i] = 1;
                    }
                }
            }
            return;
        }

        if (field.contains("-")) {
            String[] parts = field.split("-");
            int start = Integer.parseInt(parts[0]);
            int end = Integer.parseInt(parts[1]);
            
            for (int i = start; i <= end; i++) {
                if (i >= min && i <= max) {
                    targetArray[i] = 1;
                }
            }
            return;
        }

        if (field.contains(",")) {
            String[] parts = field.split(",");
            for (String part : parts) {
                int value = Integer.parseInt(part.trim());
                if (value >= min && value <= max) {
                    targetArray[value] = 1;
                }
            }
            return;
        }

        int value = Integer.parseInt(field);
        if (value >= min && value <= max) {
            targetArray[value] = 1;
        }
    }

    public long getNextExecutionTime() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = now.plusMinutes(1).withSecond(0).withNano(0);

        while (!this.matches(next)) {
            next = next.plusMinutes(1);
            if (next.isAfter(now.plusYears(1))) {
                throw new IllegalStateException("Could not find next execution time for cron expression: " + this.expression);
            }
        }

        return next.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private boolean matches(LocalDateTime dateTime) {
        int minute = dateTime.getMinute();
        int hour = dateTime.getHour();
        int dayOfMonth = dateTime.getDayOfMonth();
        int month = dateTime.getMonthValue();
        int dayOfWeek = dateTime.getDayOfWeek().getValue() % 7;

        return this.minutes[minute] == 1 &&
               this.hours[hour] == 1 &&
               this.daysOfMonth[dayOfMonth] == 1 &&
               this.months[month] == 1 &&
               (this.daysOfWeek[dayOfWeek] == 1 || this.daysOfWeek[7] == 1);
    }
}