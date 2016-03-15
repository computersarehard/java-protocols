package computersarehard.protocols;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * A protocol definition. New implementations can be registered with {@link #extend(Class, Object)} and the dispaptch
 * instance (used to invoke protocol methods) can be obtained with {@link #getDispatcher()}.
 *
 * Instances of this class are created when defining a protocol using the {@link Protocols#define(Class)} factory
 * method.
 *
 * @param <T> The protocol's type.
 */
public final class Protocol<T> {
    private final T dispatcher;

    Protocol (T dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Extends a type with a protocol implementation for this protocol.
     *
     * The registered implementation will be used for all types that inherit or implement the specified type unless
     * a more specific (closer in the type hierarchy) implementation is provided. Implementations can only be registered
     * once per specific type.
     *
     * @param type The type to extend with a protocol implementation. Can be a Class or Interface. All classes extending
     * or implementing this type will also inherit the protocol implementation unless they have been extended with
     * a more specific (closer in the type hierarchy) implementation. The {@link Void} class can be used to dispatch on
     * null values.
     * @param implementation The implementation of the protocol for the type to be extended.
     */
    public void extend (Class<?> type, T implementation) {
        ((Protocols.Dispatcher) dispatcher).protocols$$extend(type, implementation);
    }

    /**
     * Returns an instance of the protocol's interface that must be used to invoke protocol methods.
     *
     * @return an instance of the protocol's interface that must be used to invoke protocol methods.
     */
    public T getDispatcher () {
        return dispatcher;
    }
}
