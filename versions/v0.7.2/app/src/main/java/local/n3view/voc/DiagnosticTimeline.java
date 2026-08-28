package local.n3view.voc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** Bounded, in-memory event history. Nothing is transmitted or persisted automatically. */
final class DiagnosticTimeline {
    record Event(long elapsedMillis, String category, String message) {
    }

    private final int capacity;
    private final LongSupplier clock;
    private final long startedAt;
    private final ArrayDeque<Event> events = new ArrayDeque<>();

    DiagnosticTimeline(int capacity, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.clock = clock;
        startedAt = clock.getAsLong();
    }

    synchronized void add(String category, String message) {
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(new Event(
                Math.max(0, clock.getAsLong() - startedAt),
                sanitize(category),
                sanitize(message)));
    }

    synchronized List<Event> snapshot() {
        return new ArrayList<>(events);
    }

    synchronized String export() {
        StringBuilder result = new StringBuilder();
        int index = 0;
        for (Event event : events) {
            result.append(String.format(Locale.ROOT,
                    "event[%03d]=+%dms [%s] %s%n",
                    index++, event.elapsedMillis(), event.category(), event.message()));
        }
        return result.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
