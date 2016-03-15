package computersarehard.protocols;

import org.junit.Test;
import org.junit.Before;
import static org.junit.Assert.*;

public class ProtocolsTest {
    private Protocol<Reversable> reversable;

    @Before
    public void setup () {
        reversable = Protocols.define(Reversable.class);
    }

    @Test
    public void testDefine () {
        assertNotNull("returned instance is not null", reversable);
        assertTrue(
            "returned instance contains a dispatcher that implements interface",
            reversable.getDispatcher() instanceof Reversable
        );
    }

    @Test
    public void testProtocolClassMustBeInterface () {
        try {
            Protocols.define(Object.class);
        } catch (Exception e) {
            assertEquals("Protocol class must be an interface.", e.getMessage());
        }
    }

    @Test
    public void testProtocolMethodsMustHaveArguments () {
        try {
            Protocols.validateProtocol(NoArgsProtocol.class);
        } catch (Exception e) {
            assertEquals("Protocol method test does not take any parameters.", e.getMessage());
        }
    }

    @Test
    public void testProtocolMethodsMustHaveReferenceFirstArg () {
        try {
            Protocols.validateProtocol(PrimitiveProtocol.class);
        } catch (Exception e) {
            assertEquals("Protocol method test does not take reference type as first argument.", e.getMessage());
        }

        try {
            Protocols.validateProtocol(ArrayProtocol.class);
        } catch (Exception e) {
            assertEquals("Protocol method test does not take reference type as first argument.", e.getMessage());
        }
    }

    @Test
    public void testDefiningProtocolAgainReturnsNewInstance () {
        assertNotNull(Protocols.define(Reversable.class));
    }

    @Test
    public void testExactMatchDispatch () {
        reversable.extend(String.class, value -> reverseString((String) value));
        assertEquals("String is reversed.", "cba", reversable.getDispatcher().reverse("abc"));
    }

    @Test(expected = IllegalStateException.class)
    public void testTypeCanOnlyBeExtendedOnce () {
        reversable.extend(Integer.class, v -> Integer.valueOf(reverseString(v.toString())));
        assertEquals("Integer is reversed", 321, reversable.getDispatcher().reverse(123));
        reversable.extend(Integer.class, v -> v);
    }

    @Test
    public void testInterfaceDispatch () {
        reversable.extend(CharSequence.class, v -> reverseString((CharSequence) v));
        assertEquals("StringBuilder is reversed", "cba", reversable.getDispatcher().reverse(new StringBuilder("abc")));
        assertEquals("StringBuffer is reversed", "cba", reversable.getDispatcher().reverse(new StringBuffer("abc")));
    }

    @Test
    public void testNullDispatch () {
        reversable.extend(Void.class, v -> "llun");
        assertEquals("Constant is returned for void", "llun", reversable.getDispatcher().reverse(null));
    }

    public static interface Reversable {
        public Object reverse (Object value);
    }

    private static String reverseString (CharSequence s) {
        return new StringBuilder(s).reverse().toString();
    }

    public static interface NoArgsProtocol {
        public void test ();
    }

    public static interface PrimitiveProtocol {
        public void test (int test);
    }

    public static interface ArrayProtocol {
        public void test (Object[] test);
    }
}
