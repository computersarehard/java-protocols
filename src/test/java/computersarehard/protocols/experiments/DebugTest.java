package computersarehard.protocols.experiments;

import computersarehard.protocols.Protocols;
import computersarehard.protocols.Protocol;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;;
import java.time.temporal.TemporalAccessor;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;

import org.junit.Test;
import static org.junit.Assert.*;

public class DebugTest {
    @Test
    public void testNullDebugging () {
        assertEquals("<null>", Debugging.format(null));
    }

    @Test
    public void testToStringFallback () {
        assertEquals("testing", Debugging.format("testing"));
        Object o = new Object();
        assertEquals(o.toString(), Debugging.format(o));
    }

    @Test
    public void testCollection () {
        assertEquals(
            "Debug is used on elements of collection",
            "[1, 2, three, [5, 6], <null>]",
            Debugging.format(Arrays.asList(1, 2, "three", Arrays.asList(5, 6), null))
        );

        assertEquals("An empty collection is []", "[]", Debugging.format(Collections.emptyList()));
    }

    @Test
    public void testInstant () {
        assertEquals("1970-01-01T00:00:00Z", Debugging.format(Instant.ofEpochSecond(0)));
    }

    @Test
    public void testLocalDate () {
        assertEquals("1970-01-01T00:00:00Z", Debugging.format(LocalDate.of(1970, 1, 1)));
    }

    @Test
    public void testLocalDateTIme () {
        assertEquals("1970-01-01T00:00:00Z", Debugging.format(LocalDateTime.of(1970, 1, 1, 0, 0, 0)));
    }

    @Test
    public void testOffsetDateTime () {
        assertEquals(
            "1970-01-01T00:00:00Z",
            Debugging.format(OffsetDateTime.of(LocalDateTime.of(1970, 1, 1, 0, 0, 0), ZoneOffset.ofHours(0)))
        );
    }

    @Test
    public void testZonedDateTime () {
        assertEquals(
            "1970-01-01T00:00:00Z",
            Debugging.format(ZonedDateTime.of(LocalDateTime.of(1970, 1, 1, 0, 0, 0), ZoneId.of("Z")))
        );
    }
}

class Debugging {
    @SuppressWarnings("rawtypes")
    private static final Protocol<Debug> PROTOCOL = Protocols.define(Debug.class);
    @SuppressWarnings("unchecked")
    private static final Debug<Object> DEBUG = PROTOCOL.getDispatcher();

    static {
        PROTOCOL.extend(Object.class, new Debug<Object> () {
            @Override
            public String format (Object value) {
                return value.toString();
            }
        });
        PROTOCOL.extend(Void.class, new Debug<Object> () {
            @Override
            public String format (Object value) {
                return "<null>";
            }
        });
        PROTOCOL.extend(Collection.class, new Debug<Collection<?>> () {
            @Override
            public String format (Collection<?> value) {
                return "[" + value.stream().map(Debugging::format).collect(Collectors.joining(", ")) + "]";
            }
        });

        // Format common date/time types in ISO instant format

        // This handles Instant, OffsetDateTime, ZonedDateTime but will crash on others that cannot be formatted using
        // ISO_INSTANT.
        PROTOCOL.extend(TemporalAccessor.class, new Debug<TemporalAccessor> () {
            @Override
            public String format (TemporalAccessor value) {
                return DateTimeFormatter.ISO_INSTANT.format(value);
            }
        });
        // Overrides for types that need conversion
        PROTOCOL.extend(LocalDate.class, new Debug<LocalDate> () {
            @Override
            public String format (LocalDate value) {
                // hard coding 0 for consistent test execution. In an application you might use the system offset.
                return DateTimeFormatter.ISO_INSTANT.format(value.atStartOfDay().toInstant(ZoneOffset.ofHours(0)));
            }
        });
        PROTOCOL.extend(LocalDateTime.class, new Debug<LocalDateTime> () {
            @Override
            public String format (LocalDateTime value) {
                return DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.ofHours(0)));
            }
        });
    }

    public static String format (Object value) {
        return DEBUG.format(value);
    }

    public static interface Debug<T> {
        String format (T value);
    }
}
