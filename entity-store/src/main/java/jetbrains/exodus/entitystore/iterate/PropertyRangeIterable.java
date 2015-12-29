/**
 * Copyright 2010 - 2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jetbrains.exodus.entitystore.iterate;

import jetbrains.exodus.ByteIterable;
import jetbrains.exodus.bindings.ComparableBinding;
import jetbrains.exodus.bindings.LongBinding;
import jetbrains.exodus.entitystore.*;
import jetbrains.exodus.entitystore.tables.PropertyTypes;
import jetbrains.exodus.entitystore.tables.PropertyValue;
import jetbrains.exodus.env.Cursor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@SuppressWarnings({"unchecked"})
public final class PropertyRangeIterable extends EntityIterableBase {

    private final int entityTypeId;
    private final int propertyId;
    @NotNull
    private final Comparable min;
    @NotNull
    private final Comparable max;

    static {
        registerType(getType(), new EntityIterableInstantiator() {
            @Override
            public EntityIterableBase instantiate(PersistentStoreTransaction txn, PersistentEntityStoreImpl store, Object[] parameters) {
                try {
                    long min = Long.parseLong((String) parameters[2]);
                    long max = Long.parseLong((String) parameters[3]);
                    return new PropertyRangeIterable(store,
                            Integer.valueOf((String) parameters[0]), Integer.valueOf((String) parameters[1]), min, max);
                } catch (NumberFormatException e) {
                    return new PropertyRangeIterable(store,
                            Integer.valueOf((String) parameters[0]), Integer.valueOf((String) parameters[1]),
                            (Comparable) parameters[2], (Comparable) parameters[3]);
                }
            }
        });
    }

    public PropertyRangeIterable(@NotNull final PersistentEntityStoreImpl store, final int entityTypeId, final int propertyId,
                                 @NotNull final Comparable minValue, @NotNull final Comparable maxValue) {
        super(store);
        this.entityTypeId = entityTypeId;
        this.propertyId = propertyId;
        min = PropertyTypes.toLowerCase(minValue);
        max = PropertyTypes.toLowerCase(maxValue);
    }

    public int getEntityTypeId() {
        return entityTypeId;
    }

    @Override
    public boolean isSortedById() {
        return false;
    }

    @Override
    @NotNull
    public EntityIteratorBase getIteratorImpl(@NotNull final PersistentStoreTransaction txn) {
        // first, look for cached properties iterable (whole index in-memory)
        final EntityIterableCacheImpl iterableCache = getStore().getEntityIterableCache();
        final PropertiesIterable propertiesIterable = new PropertiesIterable(getStore(), entityTypeId, propertyId);
        final EntityIterableBase it = iterableCache.putIfNotCached(propertiesIterable);
        if (it.isCachedInstance()) {
            final Class<? extends Comparable> minClass = min.getClass();
            final Class<? extends Comparable> maxClass = max.getClass();
            final UpdatablePropertiesCachedInstanceIterable wrapper = (UpdatablePropertiesCachedInstanceIterable) it;
            if (minClass != maxClass || minClass != wrapper.getPropertyValueClass()) {
                return EntityIteratorBase.EMPTY;
            }
            return wrapper.getPropertyRangeIterator(min, max);
        }
        final Cursor valueIdx = openCursor(txn);
        if (valueIdx == null) {
            return EntityIteratorBase.EMPTY;
        }
        return new PropertyRangeIterator(valueIdx);
    }

    @Override
    @NotNull
    protected EntityIterableHandle getHandleImpl() {
        return new ConstantEntityIterableHandle(getStore(), getType()) {

            @Override
            public void toString(@NotNull final StringBuilder builder) {
                super.toString(builder);
                builder.append(entityTypeId);
                builder.append('-');
                builder.append(propertyId);
                builder.append('-');
                builder.append(min.toString());
                builder.append('-');
                builder.append(max.toString());
            }

            @Override
            public void hashCode(@NotNull final EntityIterableHandleHash hash) {
                hash.apply(entityTypeId);
                hash.applyDelimiter();
                hash.apply(propertyId);
                hash.applyDelimiter();
                hash.apply(min.toString());
                hash.applyDelimiter();
                hash.apply(max.toString());
            }

            @Override
            public boolean isMatchedPropertyChanged(final int typeId,
                                                    final int propertyId,
                                                    @Nullable final Comparable oldValue,
                                                    @Nullable final Comparable newValue) {
                //noinspection OverlyComplexBooleanExpression
                return PropertyRangeIterable.this.propertyId == propertyId && entityTypeId == typeId &&
                        (isRangeAffected(oldValue) || isRangeAffected(newValue));
            }

            private boolean isRangeAffected(Comparable value) {
                if (value == null) {
                    return false;
                }
                value = PropertyTypes.toLowerCase(value);
                return min.compareTo(value) <= 0 && max.compareTo(value) >= 0;
            }
        };
    }

    private static EntityIterableType getType() {
        return EntityIterableType.ENTITIES_BY_PROP_VALUE_IN_RANGE;
    }

    @Override
    protected long countImpl(@NotNull final PersistentStoreTransaction txn) {
        final Cursor cursor = openCursor(txn);
        if (cursor == null) {
            return 0;
        }
        try {
            final PropertyValue propertyValue = getStore().getPropertyTypes().dataToPropertyValue(min);
            final ComparableBinding binding = propertyValue.getBinding();
            long result = 0;
            boolean success = cursor.getSearchKeyRange(propertyValue.dataToEntry()) != null;
            while (success) {
                if (max.compareTo(binding.entryToObject(cursor.getKey())) < 0) {
                    break;
                }
                result += cursor.count();
                success = cursor.getNextNoDup();
            }
            return result;
        } finally {
            cursor.close();
        }
    }

    private Cursor openCursor(@NotNull final PersistentStoreTransaction txn) {
        return getStore().getPropertyValuesIndexCursor(txn, entityTypeId, propertyId);
    }

    private final class PropertyRangeIterator extends EntityIteratorBase {

        private boolean hasNext;

        @NotNull
        private final ComparableBinding binding;

        private PropertyRangeIterator(@NotNull final Cursor cursor) {
            super(PropertyRangeIterable.this);
            setCursor(cursor);
            binding = getStore().getPropertyTypes().dataToPropertyValue(min).getBinding();
            ByteIterable key = binding.objectToEntry(min);
            checkHasNext(getCursor().getSearchKeyRange(key) != null);
        }

        @Override
        public boolean hasNextImpl() {
            return hasNext;
        }

        @Override
        @Nullable
        public EntityId nextIdImpl() {
            if (hasNextImpl()) {
                explain(getType());
                final Cursor cursor = getCursor();
                final EntityId result = new PersistentEntityId(entityTypeId, LongBinding.compressedEntryToLong(cursor.getValue()));
                checkHasNext(cursor.getNext());
                return result;
            }
            return null;
        }

        private void checkHasNext(final boolean success) {
            hasNext = success && max.compareTo(binding.entryToObject(getCursor().getKey())) >= 0;
        }
    }
}
