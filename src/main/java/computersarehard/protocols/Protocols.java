package computersarehard.protocols;

import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;

import javassist.*;

/**
 * Public API for defining protocols and extending existing types with protocol implementations.
 *
 * Protocols are defined by calling {@link Protocols#define(Class)} with a class that represents an interface that
 * defines the API for the protocol. The interface must only contain methods that have at least one argument where the
 * first argument is a reference type (not primitive nor array). The first argument to each protocol method will be used
 * as the dispatch object. {@link #define(Class)} returns a {@link Protocol} instance which provides access to an
 * instance of the interface that is then used when invoking the protocol's methods.
 *
 * Existing types are extended with protocol implementations by calling {@link Protocol#extend(Class, Object)} with
 * arguments that describe the type being extended, the protocol being implemented, and the implementation of the
 * protocol for the type.
 */
public class Protocols {
    /**
     * Defines a protocol using a certain API as defined by the {@link Class} instance passed as an argument.
     *
     * Using the passed {@link Class}, an implementation class is generated that delegates to the protocol
     * implementations registered via {@link Protocol#extend(Class, Object)}. Callers must take care to save
     * a reference to the returned instance and perform all implementation registrations and invocations of the protocol
     * through it.  This method may be called an unlimited number of times per unique {@link Class} instance, however,
     * a new instance is returend each time with no knowledge of previous instances.
     *
     * @param api A {@link Class} instance representing an interface that provides the contract for the protocol. This
     * instance _must_ represent an interface, only have methods with arguments where the first argument is a reference
     * type.
     * @param <T> The protocol's type.
     *
     * @return The {@link Protocol} registration object.
     */
    @SuppressWarnings("unchecked")
    public static <T> Protocol<T> define (Class<T> api) {
        checkNotNull(api, "api argument is required.");
        validateProtocol(api);

        try {
            return new Protocol<>(makeDispatcher(api));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void validateProtocol (Class<?> api) {
        // Must be an interface
        if (!api.isInterface()) {
            throw new IllegalArgumentException("Protocol class must be an interface.");
        }
        // All methods must take at least one argument
        for (Method m : api.getMethods()) {
            if ((m.getModifiers() & Modifier.STATIC) > 0) {
                continue;
            }
            // Must have at least one parameter
            if (m.getParameterCount() == 0) {
                throw new IllegalArgumentException("Protocol method " + m.getName() + " does not take any parameters.");
            }
            // First parameter must be a reference type
            Class<?> firstParamType = m.getParameterTypes()[0];
            if (firstParamType.isPrimitive() || firstParamType.isArray()) {
                throw new IllegalArgumentException("Protocol method " + m.getName() + " does not take reference type as"
                    + " first argument.");
            }
        }
    }

    private static <T> T makeDispatcher (Class<T> api) throws Exception {
        // Create a new class with a name similar to the protocol's name (or find a previously created class)
        final String className = api.getName() + "$$Protocol";
        try {
            // This is actually the edge case. Typically this method is only called once per protocol and classloader.
            return newDispatcher(api.getClassLoader().loadClass(className), api);
        } catch (ClassNotFoundException e) {
            // Generate a proxy class for the given interface. The proxy will delegate all method invocations to the
            // matching protocol implementation for the first argument's type.
            ClassPool cp = new ClassPool();
            cp.appendClassPath(new LoaderClassPath(api.getClassLoader()));

            CtClass proxyStubClass = cp.get(Dispatcher.class.getName());
            CtClass apiClass = cp.get(api.getName());

            CtClass proxyClass = cp.makeClass(className);

            // Create a subclass.
            // Proxy class extends `Dispatcher` and gains the utility methods and state management for type dispatch.
            proxyClass.setSuperclass(proxyStubClass);
            // Proxy class implements the protocol interface.
            proxyClass.setInterfaces(new CtClass[] {apiClass});

            // Add an implementation for every method in the protocol interface.
            for (CtMethod m : apiClass.getDeclaredMethods()) {
                CtMethod copy = new CtMethod(m, proxyClass, null);
                // The body of the method looks up the implementation based on the type of the first argument and then
                // delegates the method call to that implementation.
                copy.setBody(
                    "return ((" + api.getName() + ")protocols$$getImplementation($1))." + m.getName() + "($$);"
                );
                proxyClass.addMethod(copy);
            }

            // Create a one arg constructor that calls super.
            proxyClass.addConstructor(CtNewConstructor.make(
                new CtClass[] {cp.get(Class.class.getName())},
                new CtClass[0],
                proxyClass
            ));

            return newDispatcher(proxyClass.toClass(api.getClassLoader(), api.getProtectionDomain()), api);
        }
    }

    private static <T> T newDispatcher (Class<?> generatedClass, Class<T> api) throws Exception {
        // Create an instance
        @SuppressWarnings("unchecked")
        T dispatcher = (T) generatedClass.getConstructor(Class.class).newInstance(api);
        return dispatcher;
    }

    /**
     * A protocol definition and call dispatcher.
     *
     * Encapsulates the state of a protocol definition (registered implementations) and provides type dispatch
     * utilities. A subclass of this class is generated and instantiated for each registered protocol. The methods in
     * the protocol's interface are then stubbed out to delegate calls to the appropriate type based on the dispatch
     * argument.
     */
    protected static class Dispatcher {
        private final Object lock = new Object();
        private final Class<?> api;
        private volatile Map<Class<?>, Object> implementations = new IdentityHashMap<>();
        private volatile Map<Class<?>, Object> dispatchTable = new IdentityHashMap<>();

        /**
         * Initializes an instance with the given protocol API definition.
         *
         * @param api A {@link Class} instance representing an interface that provides the contract for the protocol.
         */
        protected Dispatcher (Class<?> api) {
            this.api = api;
        }

        void protocols$$extend (Class<?> type, Object implementation) {
            synchronized (lock) {
                if (implementations.containsKey(type)) {
                    throw new IllegalStateException("Protocol  " + api.getName() + " already has an implementation for"
                        + " type " + type.getName() + ".");
                }

                Map<Class<?>, Object> copy = new IdentityHashMap<>(implementations);
                copy.put(type, implementation);

                implementations = copy;
                // Reset the dispatch table cache. The initial cache contains any types that are directly registered.
                dispatchTable = new IdentityHashMap<>(implementations);
            }
        }

        /**
         * Determines the protocol implementation to delegate to for the given dispatch argument. Lookups are cached
         * based on the argument's type. Caches are cleared when the implementation registry changes. Precedence is:
         *
         * * Class
         * * Super-classes (excluding Object)
         * * Interfaces
         * * Super-class' interfaces
         * * Object
         *
         * @param target The dispatch object who's type will be used to perform the implementation lookup.
         * @return The protocol implementation or null if no match was found.
         */
        protected final Object protocols$$getImplementation (Object target) {
            Class<?> dispatchType;
            // use Void in place of null
            if (target == null) {
                dispatchType = Void.class;
            } else {
                dispatchType = target.getClass();
            }
            // Check the cache
            Object implementation = dispatchTable.get(dispatchTable);

            if (implementation != null) {
                return implementation;
            }

            // Traverse the type hierarchy to find an implementation
            implementation = findImplementation(dispatchType);
            // Error if no implementation was found for dispatchType
            if (implementation == null) {
                throw new IllegalArgumentException("Protocol " + api.getName() + " is not defined for "
                    + dispatchType.getName());
            } else {
                // Update the cache
                synchronized (lock) {
                    Map<Class<?>, Object> copy = new IdentityHashMap<>(dispatchTable);
                    copy.put(dispatchType, implementation);
                    dispatchTable = copy;
                }
                return implementation;
            }
        }

        private Object findImplementation (Class<?> dispatchClass) {
            if (dispatchClass == null) {
                return null;
            }

            // Do we have a matching implementation?
            Object implementation = implementations.get(dispatchClass);
            if (implementation != null) {
                return implementation;
            }

            // Do our super-classes have an implementation?
            implementation = findImplInSuperclasses(dispatchClass);
            if (implementation != null) {
                return implementation;
            }

            // Do our interfaces or our super-classes have an implementation?
            implementation = findImplInInterfaces(dispatchClass);
            if (implementation != null) {
                return implementation;
            }

            // Does Object have an implementation?
            return implementations.get(Object.class);
        }

        private Object findImplInSuperclasses (Class<?> dispatchClass) {
            Object implementation;
            // Check all super classes (minus Object)
            Class<?> superclass = dispatchClass.getSuperclass();
            // Object's superclass is null. Avoid checking for Object.class explicitly due to potential classloader
            // issues.
            while (superclass != null && superclass.getSuperclass() != null) {
                implementation = implementations.get(superclass);
                if (implementation != null) {
                    return implementation;
                }
                superclass = superclass.getSuperclass();
            }

            return null;
        }

        private Object findImplInInterfaces (Class<?> dispatchClass) {
            if (dispatchClass == null) {
                return null;
            }

            Object implementation = implementations.get(dispatchClass);
            if (implementation != null) {
                return implementation;
            }

            // Traverse interfaces in order
            for (Class<?> i : dispatchClass.getInterfaces()) {
                // Traverse super-interfaces
                implementation = findImplInInterfaces(i);
                if (implementation != null) {
                    return implementation;
                }
            }

            // Also look through super-classes interfaces
            return findImplInInterfaces(dispatchClass.getSuperclass());
        }
    }

    private static void checkNotNull (Object o, String message) {
        if (o == null) {
            throw new IllegalArgumentException(message);
        }
    }
}
