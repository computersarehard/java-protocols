package computersarehard.protocols.experiments;

import computersarehard.protocols.Protocols;
import computersarehard.protocols.Protocol;

import java.beans.PropertyDescriptor;
import java.beans.Introspector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.Test;
import static org.junit.Assert.*;

public class AssociativeTest {
    @Test
    public void testMaps () {
        Map<String, Integer> map = new HashMap<>();

        assertEquals(null, Associatives.put(map, "test", 1));
        assertEquals(1, Associatives.get(map, "test"));
        assertEquals(1, Associatives.put(map, "test", 2));
        assertEquals(2, Associatives.get(map, "test"));
    }

    @Test
    public void testLists () {
        List<String> list = new ArrayList<>();

        assertEquals(null, Associatives.put(list, 0, "test"));
        assertEquals("test", Associatives.get(list, 0));
        assertEquals("test", Associatives.put(list, 0, "again"));
        assertEquals("again", Associatives.get(list, 0));
        assertEquals(null, Associatives.put(list, 10, "ten"));
        assertEquals("ten", Associatives.get(list, 10));
    }

    @Test
    public void testMergeListMap () {
        Map<Integer, String> map = new HashMap<>();
        map.put(3, "three");
        map.put(4, "four");
        map.put(5, "five");

        List<String> list = new ArrayList<>();
        // These will be added at keys 0-2
        list.add("zero");
        list.add("one");
        list.add("two");
        // this should override value with key 3
        list.add("THREE");

        Associatives.merge(map, list);
        assertEquals("zero", map.get(0));
        assertEquals("one", map.get(1));
        assertEquals("two", map.get(2));
        assertEquals("THREE", map.get(3));
        assertEquals("four", map.get(4));
        assertEquals("five", map.get(5));
    }

    @Test
    public void testObject () {
        TestBean bean = new TestBean(1, "test");

        assertEquals(1, Associatives.get(bean, "id"));
        assertEquals("test", Associatives.get(bean, "value"));

        assertEquals(1, Associatives.put(bean, "id", 2));
        assertEquals(2, Associatives.get(bean, "id"));


        Map<String, Object> map = new HashMap<>();

        // Merge into a map
        Associatives.merge(map, bean);
        assertEquals(2, map.get("id"));
        assertEquals("test", map.get("value"));

        map.put("id", 4);
        map.put("value", "map");

        // Merge back into a bean
        Associatives.merge(bean, map);
        assertEquals(4, bean.getId());
        assertEquals("map", bean.getValue());
    }
}

class TestBean {
    private int id;
    private String value;

    public TestBean (int id, String value) {
        this.id = id;
        this.value = value;
    }

    public int getId () {
        return id;
    }

    public void setId (int id) {
        this.id = id;
    }

    public String getValue () {
        return value;
    }

    public void setValue (String value) {
        this.value = value;
    }
}

class Associatives {
    @SuppressWarnings("rawtypes")
    private static final Protocol<Associative> PROTOCOL = Protocols.define(Associative.class);
    @SuppressWarnings("unchecked")
    private static final Associative<Object, Object, Object> ASSOCIATIVE = PROTOCOL.getDispatcher();

    static {
        // Adapt Map
        PROTOCOL.extend(Map.class, new Associative<Map<Object, Object>, Object, Object> () {
            @Override
            public Object put (Map<Object, Object> container, Object key, Object value) {
                return container.put(key, value);
            }

            @Override
            public Object get (Map<Object, Object> container, Object key) {
                return container.get(key);
            }

            @Override
            public Iterable<Object> keys (Map<Object, Object> container) {
                return container.keySet();
            }
        });

        // Adapt List to Integer -> Object
        PROTOCOL.extend(List.class, new Associative<List<Object>, Integer, Object> () {
            @Override
            public Object put (List<Object> container, Integer key, Object value) {
                if (key < 0) {
                    throw new IndexOutOfBoundsException("Index: " + key);
                }
                if (key == container.size()) {
                    // if key is exactly the size we can use add to extend the list one.
                    container.add(key, value);
                    return null;
                } else if (key > container.size()) {
                    // If key is greater than the size then we need to extend the list out to make room.
                    for (int i = 0, n = key - container.size(); i <= n; i++) {
                        container.add(null);
                    }
                }
                return container.set(key, value);
            }

            @Override
            public Object get (List<Object> container, Integer key) {
                return container.get(key);
            }

            @Override
            public Iterable<Integer> keys (List<Object> container) {
                return IntStream.range(0, container.size()).boxed().collect(Collectors.toList());
            }
        });

        // Adapt Objects to String -> Object using java bean conventions
        // This is a naive and simple implementation for demonstration purposes.
        PROTOCOL.extend(Object.class, new Associative<Object, String, Object> () {
            @Override
            public Object put (Object container, String key, Object value) {
                try {
                    PropertyDescriptor pd = getPropertyDescriptor(container, key);

                    Object oldValue = pd.getReadMethod().invoke(container);
                    pd.getWriteMethod().invoke(container, value);

                    return oldValue;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reflect on " + container.getClass() + ", key " + key, e);
                }
            }

            @Override
            public Object get (Object container, String key) {
                try {
                    return getPropertyDescriptor(container, key).getReadMethod().invoke(container);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reflect on " + container.getClass() + ", key " + key, e);
                }
            }

            @Override
            public Iterable<String> keys (Object container) {
                try {
                    List<String> keys = new ArrayList<>();
                    PropertyDescriptor[] pds = Introspector.getBeanInfo(container.getClass()).getPropertyDescriptors();
                    for (PropertyDescriptor pd : pds) {
                        // reflection can be problematic. A real implementation would need to take care to only expose
                        // read/write methods, etc.
                        if (!pd.getName().equals("class")) {
                            keys.add(pd.getName());
                        }
                    }
                    return keys;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to reflect on " + container.getClass(), e);
                }
            }

            private PropertyDescriptor getPropertyDescriptor (Object o, String key) throws Exception {
                return new PropertyDescriptor(key, o.getClass());
            }
        });
    }

    //Support merge o2 into o1 as long as both implement "Associative". Key types must be compatible.
    public static void merge (Object o1, Object o2) {
        Associatives.keys(o2).forEach(k -> {
            Associatives.put(o1, k, Associatives.get(o2, k));
        });
    }

    public static Object get (Object container, Object key) {
        return ASSOCIATIVE.get(container, key);
    }

    public static Object put (Object container, Object key, Object value) {
        return ASSOCIATIVE.put(container, key, value);
    }

    // Using `Map.Entry` would be nicer, but this is simple for demonstration.
    public static Iterable<Object> keys (Object container) {
        return ASSOCIATIVE.keys(container);
    }

    public static interface Associative<C, K, V> {
        V put (C container, K key, V value);
        V get (C container, K key);
        Iterable<K> keys (C container);
    }
}
