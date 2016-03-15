# Overview

Protocols is an small, experimental Java library that implements a protocol
concept similar to [Clojure Protocols][clojure-protocols]. Protocols provide
dynamic polymorphism where existing types may be extended at runtime. In other
words:

    // Define the protocol's API
    interface Increment<T> {
        T increment (T val);
    }

    // Register and create the protocol dispatcher
    Protocol<Increment> increment = Protocols.define(Increment.class);

    // Extend existing types with an implementation
    increment.extend(Integer.class, new Increment<Integer> () {
        public Integer increment (Integer value) {
            return value + 1;
        }
    });

    increment.extend(BigInteger.class, new Increment<BigInteger> () {
        public BigInteger increment (BigInteger value) {
            return value.add(BigInteger.valueOf(1));
        }
    });

    // Invoke protocol methods
    increment.getDispatcher().increment(BigInteger.valueOf(20)); // -> BigInteger(21)
    increment.getDispatcher().increment(20); // -> Integer(21)

# Why?

Separating behavior from types gives a lot more flexibility and power to
application and API developers. Existing types throughout the ecosystem can be
integrated with new APIs without the need for wrappers or one off dispatch
tables. An example use in Clojure that, I think, illustrates the point well is
the use of protocols in [clojure.java.jdbc][clojure-jdbc]. Protocols are used
for conversion during statement preparation or result set reading. An existing
type (under the developer's control or not) can be integrated with the library
by simply providing an appropriate protocol implementation.

Of course, various Java database libraries also provide the same flexibility.
The difference, however, is in how that is achieved. Most Java libraries
solving the problem using one off registries where implementations are either
registered directly by API calls or declaratively by annotations. Each library
you integrate with uses a different mechanism. The power of protocols is that
_they are a general solution to a problem that has seen countless ad hoc
implementations accross applications and libraries._

Based on my experience with protocols in Clojure, I wondered how protocols
might work in plain ol' Java. There is a lot more flexibility in Clojure so
a Java implementation probably won't be _as_ powerful. Still, the general
concept could be implemented and still prove useful.

# Should I use this?

Probably not. This project is more of an experiment or proof of concept. There
are a few things that this project lacks:

* A comprehensive test suite covering more inheritance cases and class loader
  configurations
* Performance tuning
* Study of prior art&mdash;I wanted to go through the exercise and thus did not
  search for any existing implementations

# Design Trade-offs

Although Clojure runs on the JVM, its dynamic nature allows it to provide much
richer facilities than we can provide directly in Java without making changes
to the Java language or introducing a more complicated infrastructure (e.g.
build time code generators). Thus the ergonomics are fairly poor and a heavy
amount of boilerplate is necessary. One nice aspect of Clojure's protocols is
that you extend a type with a map of functions. While we could implement such
a system in Java, it felt much less natural due to Java's statically typed
nature. Thus, leveraging existing concepts (e.g. interfaces) seemed to be the
more appropriate way to go.  Other statically typed languages (Swift, Rust) are
able to provide rich protocol (or protocol-like) concepts by making the
functionality a first-class feature of the language.

Some protocol systems in statically typed languages allow you to define
implementations using multiple constraints. For example, implementing protocol
`CustomSerializable` for any type implementing `List` and `Serializable`. Since
dispatch is a runtime decision in this library, we could implement such
a system. This would add quite a bit of complexity and thus was outside the
scope of this experiment.

This library uses [Javassist][javassist] to generate a dispatcher classes.  The
generated class implements the protocol's interface. For each method, it
provides code that does a hash map lookup to find an implementation and
a direct method invocation on that implementation. Thus best-case performance
overhead is a hash map lookup and a method call. Worst-case performance occurs
the first time a call on a new type is dispatched due to a cache miss.
`java.lang.reflect.Proxy` could have been used to remove the library dependency
but doing so results in a decent amount of reflection overhead during method
invocation.

Registration of implementations is a sore spot. Before you can start calling
into a protocol, you need to make sure that you've registered all of your
implementations. An alternative approach would be to use annotation scanning to
identify both protocols an protocol implementations and auto-register them.
Again, this is additional complexity so it was not considered for this
experiment.

# Usage

A protocol, in terms of this library, is a Java interface definition that only
has methods take at least one parameter where that parameter is a reference
type that is not typed as a primitive nor an array. This first parameter is the
_dispatch_ parameter: the parameter whose type will determine what
implementation is called. Typically this should be typed as `Object`, however,
my current suggestion is to make this a generic type in the interface so
implementations don't have to deal with casting as much. Here is an example
protocol definition that provides a function to convert an object into
a printable string useful for debugging.

    public interface Debug<T> {
        /**
         * Formats value into a string for debug logging.
         */
        public String format (T value);
    }

After defining the protocol's interface, we have to "define" the protocol which
mainly means registering it with the `Protocols` API. This registration returns
us an instance of the `Protocol` class. A reference to the returned `Protocol`
instance must be held so implementations of the protocol can be registered
through it. Method invocation is also performed through the dispatcher provided
by the `Protocol` instance. Convention is to wrap the `Protocol` instance and
method calls into the dispatcher instance with static methods. Since the
`Protocol` instance is just an instance, a dependency injection style use works
too.

Below is an example wrapper class. Writing a wrapper adds a lot of boilerplate
to the implementation but results in a friendlier API for users of your
protocol. Here is an example for the `Debug` protocol we defined above. Also
note that due to limitations in the Java type system we have to suppress some
warnings.

    @SuppressWarnings({"rawtypes", "unchecked"})
    public final class Debugging {
        // Callers can register an implementation by calling
        // `Debugging.PROTOCOL.extend(...)`
        public static final Protocol<Debug> PROTOCOL = Protocols.define(Debug.class);

        // Callers can invoke the protocol by calling `Debugging.format(...)`
        public static String format (Object value) {
            // Delegate to the dispatcher
            return PROTOCOL.getDispatcher().format(value);
        }

        // The interface can be defined anywhere, including as an inner class to the
        // wrapper.
        public static interface Debug<T> {
            /**
             * Formats value into a string for debug logging.
             */
            public String format (T value);
        }
    }

Before we make any calls into our protocol, we need some implementations! Let's
create a few default implementations that will handle the general cases.
Protocol dispatch respects type hierarchies and will use a super class or
interface implementation if an implementation is not provided for a specific
object's type. This means we can provide an implementation for Object that will
catch all types. Further, we can provide an implementation for `null` values by
using the `Void` class. We register implementations using
`Protocol.extend(type, implementation)`. For `Object` we will delegate to
the built in `toString` method which many types implement to return suitable
debugging strings.

    // in some initialization code (startup or a static block that you know will
    // load before you make a call into the protocol)

    Debugging.PROTOCOL.extend(Object.class, new Debug<Object> () {
        public String format (Object value) {
            // since nulls are dispatched to type `Void` we should never get nulls
            return value.toString();
        }
    });

    Debugging.PROTOCOL.extend(Void.class, new Debug<Object> () {
        public String format (Object value) {
            // Because we're registered to the `Void` type, we know we're always
            // going to get nulls.
            return "null";
        }
    });

We should now be able to write code against our protocol that uses our two
implementations:

    Debug.format("Testing"); // -> "Testing"
    Debug.format(null); // -> "null"
    Debug.format(Arrays.asList(1, 2, 3, 4)); // -> "[1, 2, 3, 4]"

We've already covered a lot of ground with those two implementations as many
classes provide reasonable `toString` implementations. However, may don't.
Further, collection classes will invoke `toString` on the objects they contain
which isn't what we want. We want them to pass through our `Debug` protocol.
Let's provide an implementation that will work for all classes implementing
`Collection`.

    Debugging.PROTOCOL.extend(Collection.class, new Debug<Collection<?>> () {
        public String format (Collection<?> value) {
            return "["
              + value.stream().map(Debugging::format).collect(Collectors.joining(", "))
              + "]";
        }
    });

If we want to print all instances of `java.time.temporal.TemporalAccessor` in
a ISO instant format we could provide the following implementation:

    Debugging.PROTOCOL.extend(TemporalAccessor.class, new Debug<TemporalAccessor> () {
        public String format (TemporalAccessor value) {
            return DateTimeFormatter.ISO_INSTANT.format(value);
        }
    });

We can also specialize for `TemporalAccessor` implementations that cannot be
formatted with the `ISO_INSTANT` formatter used above:

    Debugging.PROTOCOL.extend(LocalDate.class, new Debug<LocalDate> () {
        public String format (LocalDate value) {
            // hard coding 0 for consistent test execution. In an application you
            // might use the system offset.
            return DateTimeFormatter.ISO_INSTANT.format(
              value.atStartOfDay().toInstant(ZoneOffset.ofHours(0))
            );
        }
    });

    Debugging.PROTOCOL.extend(LocalDateTime.class, new Debug<LocalDateTime> () {
        public String format (LocalDateTime value) {
            return DateTimeFormatter.ISO_INSTANT.format(
              value.toInstant(ZoneOffset.ofHours(0))
            );
        }
    });

Now, we can put it all together:

    Debugging.format(Arrays.<Object>asList(
        "Test",
        1,
        2,
        3,
        Instant.ofEpochSecond(0),
        LocalDate.of(1970, 1, 1)
    )); // -> [Test, 1, 2, 3, 1970-01-01T00:00:00Z, 1970-01-01T00:00:00Z]

[clojure-protocols]: http://clojure.org/reference/protocols
[clojure-jdbc]: https://clojure.github.io/java.jdbc/
[javassist]: https://jboss-javassist.github.io/javassist/
