/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.model.collection;

import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.Nullable;

/**
 * Allows the adding of items to a named collection where instantiation is managed.
 *
 * @param <T> the contract type for all items
 */
@Incubating
public interface CollectionBuilder<T> {
    /**
     * Returns a collection containing the items from this collection of the specified type.
     *
     * @param type The type.
     * @param <S> The type.
     * @return The collection.
     */
    <S> CollectionBuilder<S> withType(Class<S> type);

    /**
     * Returns the number of items in this collection.
     * @return the size of this collection.
     */
    int size();

    /**
     * Returns the item with the given name, if any.
     *
     * @param name The name of the item.
     * @return The item, or null if no such item.
     */
    @Nullable
    T get(String name);

    /**
     * Defines an item with the given name and type T
     *
     * @param name The name.
     */
    // TODO - exception when no default type
    void create(String name);

    /**
     * Defines an item with the given name and type T. The given action is invoked to configure the item when the item is required.
     *
     * @param name The name.
     * @param configAction An action that initialises the item. The action is executed when the item is required.
     */
    // TODO - exception when no default type
    void create(String name, Action<? super T> configAction);

    /**
     * Defines an item with the given name and type.
     *
     * @param name The name.
     */
    // TODO - exception when type cannot be created
    <S extends T> void create(String name, Class<S> type);

    /**
     * Defines an item with the given name and type. The given action is used to configure the item.
     *
     * @param name The name.
     * @param configAction An action that initialises the item. The action is executed when the item is required.
     */
    // TODO - exception when type cannot be created
    <S extends T> void create(String name, Class<S> type, Action<? super S> configAction);

    /**
     * Apply the given action to the given item. The given action is invoked to configure the item when the item is required.
     */
    void named(String name, Action<? super T> configAction);

    void beforeEach(Action<? super T> configAction);

    <S extends T> void beforeEach(Class<S> type, Action<? super S> configAction);

    /**
     * Apply the given action to each item in the collection, as an item is required.
     */
    void all(Action<? super T> configAction);

    <S extends T> void withType(Class<S> type, Action<? super S> configAction);

    /**
     * Apply the given action to each item in the collection, after the item has been configured.
     */
    void finalizeAll(Action<? super T> configAction);

    <S extends T> void finalizeAll(Class<S> type, Action<? super S> configAction);
}
