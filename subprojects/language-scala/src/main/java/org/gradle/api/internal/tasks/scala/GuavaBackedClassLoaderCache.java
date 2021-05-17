/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks.scala;


import com.google.common.base.FinalizablePhantomReference;
import com.google.common.base.FinalizableReferenceQueue;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Sets;

import java.lang.ref.Reference;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Simple guava-based classloader cache. Usually used with a very small size (< 10),
 * as classloaders are strongly referenced.
 *
 * Keeping them strongly referenced allows us to correctly release resources for the evicted entries.
 */
public class GuavaBackedClassLoaderCache<K> implements AutoCloseable {
    private final Cache<K, ClassLoader> cache;

    private static final FinalizableReferenceQueue frq = new FinalizableReferenceQueue();
    private static final Set<Reference<ClassLoader>> references = Sets.newConcurrentHashSet();

    public GuavaBackedClassLoaderCache() {
        cache = CacheBuilder
            .newBuilder()
            .softValues()
            .build();
    }

    public ClassLoader get(K key, Callable<ClassLoader> loader) throws Exception {
        return cache.get(key, new Callable<ClassLoader>() {
            @Override
            public ClassLoader call() throws Exception {
                ClassLoader cl = loader.call();

                // Add a class loader finalizer using phantom reference
                FinalizablePhantomReference<ClassLoader> fpr = new FinalizablePhantomReference<ClassLoader>(cl, frq) {
                    @Override
                    public void finalizeReferent() {
                        // allow the finalizer to get garbage collected
                        references.remove(this);

                        System.err.println("DEBUG::: CLOSING CLASSLOADER !!!");

                        // class loader finalizer
                        if (cl instanceof AutoCloseable) {
                            try {
                                ((AutoCloseable) cl).close();
                            } catch(Exception ex) {
                                throw new RuntimeException("Failed to close classloader", ex);
                            }
                        }
                    }
                };
                // prevent the finalizer from getting garbage collected
                references.add(fpr);

                return cl;
            }
        });
    }

    public void clear() {
        cache.invalidateAll();
    }

    @Override
    public void close() {
        cache.invalidateAll();
    }
}
