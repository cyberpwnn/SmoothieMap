/*
 * Copyright (C) The SmoothieMap Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.timeandspace.smoothie;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import static io.timeandspace.smoothie.Utils.verifyEqual;

final class UnsafeUtils {
    static final Unsafe U;

    static final int ARRAY_OBJECT_INDEX_SHIFT =
            Integer.numberOfTrailingZeros(Unsafe.ARRAY_OBJECT_INDEX_SCALE);

    /** [Pre-casted constant] */
    static final long ARRAY_OBJECT_BASE_OFFSET_AS_LONG = (long) Unsafe.ARRAY_OBJECT_BASE_OFFSET;
    /** [Pre-casted constant] */
    static final long ARRAY_OBJECT_INDEX_SCALE_AS_LONG = (long) Unsafe.ARRAY_OBJECT_INDEX_SCALE;

    /** [Pre-casted constant] */
    static final long ARRAY_INT_BASE_OFFSET_AS_LONG = (long) Unsafe.ARRAY_INT_BASE_OFFSET;
    /**
     * [Pre-casted constant]. Oddly, {@link Unsafe#ARRAY_INT_INDEX_SCALE} is not a compile-time
     * constant in OpenJDK.
     */
    static final long ARRAY_INT_INDEX_SCALE_AS_LONG = Integer.BYTES;

    static {
        verifyEqual(ARRAY_INT_INDEX_SCALE_AS_LONG, (long) Unsafe.ARRAY_INT_INDEX_SCALE);
    }

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            U = (Unsafe) theUnsafe.get(null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static long minInstanceFieldOffset(Class<?> objectClass) {
        // U::objectFieldOffset triggers forbidden-apis of Objects.requireNonNull() for some reason
        //noinspection Convert2MethodRef
        return Stream
                .of(objectClass.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()))
                .mapToLong((Field field) -> U.objectFieldOffset(field))
                .min()
                .getAsLong();
    }

    static long getFieldOffset(Class<?> objectClass, String fieldName) {
        try {
            Field field = objectClass.getDeclaredField(fieldName);
            return U.objectFieldOffset(field);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    /** Polyfill implementation of Java 9's {@code VarHandle.releaseFence()}. */
    static void releaseFence() {
        U.storeFence();
    }

    /** Polyfill implementation of Java 9's {@code VarHandle.storeStoreFence()}. */
    static void storeStoreFence() {
        U.storeFence();
    }

    /** Polyfill implementation of Java 9's {@code VarHandle.acquireFence()}. */
    static void acquireFence() {
        U.loadFence();
    }

    private UnsafeUtils() {
    }
}
