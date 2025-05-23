/*
 * Copyright (c) 2008-2025, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.internal.metrics.impl;

import static com.hazelcast.internal.metrics.impl.ProbeType.getType;
import static java.lang.String.format;

import com.hazelcast.internal.metrics.DoubleProbeFunction;
import com.hazelcast.internal.metrics.LongProbeFunction;
import com.hazelcast.internal.metrics.MetricDescriptor;
import com.hazelcast.internal.metrics.Probe;
import com.hazelcast.internal.metrics.ProbeFunction;
import com.hazelcast.internal.util.counters.Counter;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * A FieldProbe is a {@link ProbeFunction} that reads out a field that is annotated with {@link Probe}.
 */
abstract class FieldProbe implements ProbeFunction {

    final CachedProbe probe;
    final Field field;
    final ProbeType type;
    final SourceMetadata sourceMetadata;
    final String probeName;

    FieldProbe(Field field, Probe probe, ProbeType type, SourceMetadata sourceMetadata) {
        this.field = field;
        this.probe = new CachedProbe(probe);
        this.type = type;
        this.sourceMetadata = sourceMetadata;
        this.probeName = probe.name();
        assert probeName != null;
        assert probeName.length() > 0;
        field.setAccessible(true);
    }

    void register(MetricsRegistryImpl metricsRegistry, Object source, String namePrefix) {
        MetricDescriptor descriptor = metricsRegistry
                .newMetricDescriptor()
                .withPrefix(namePrefix)
                .withMetric(getProbeName());
        metricsRegistry.registerInternal(source, descriptor, probe.level(), this);
    }

    void register(MetricsRegistryImpl metricsRegistry, MetricDescriptor descriptor, Object source) {
        metricsRegistry.registerStaticProbe(source, descriptor, getProbeName(), probe.level(), probe.unit(), this);
    }

    String getProbeName() {
        return probeName;
    }

    static <S> FieldProbe createFieldProbe(Field field, Probe probe, SourceMetadata sourceMetadata) {
        ProbeType type = getType(field.getType());
        if (type == null) {
            throw new IllegalArgumentException(format("@Probe field '%s' is of an unhandled type", field));
        }

        if (type.getMapsTo() == double.class) {
            return new DoubleFieldProbe<S>(field, probe, type, sourceMetadata);
        } else if (type.getMapsTo() == long.class) {
            return new LongFieldProbe<S>(field, probe, type, sourceMetadata);
        } else {
            throw new IllegalArgumentException(type.toString());
        }
    }

    static class LongFieldProbe<S> extends FieldProbe implements LongProbeFunction<S> {

        LongFieldProbe(Field field, Probe probe, ProbeType type, SourceMetadata sourceMetadata) {
            super(field, probe, type, sourceMetadata);
        }

        @Override
        public long get(S source) throws Exception {
            switch (type) {
                case TYPE_LONG_PRIMITIVE:
                    return field.getLong(source);
                case TYPE_LONG_NUMBER:
                    Number longNumber = (Number) field.get(source);
                    return longNumber == null ? 0 : longNumber.longValue();
                case TYPE_MAP:
                    Map<?, ?> map = (Map<?, ?>) field.get(source);
                    return map == null ? 0 : map.size();
                case TYPE_COLLECTION:
                    Collection<?> collection = (Collection<?>) field.get(source);
                    return collection == null ? 0 : collection.size();
                case TYPE_COUNTER:
                    Counter counter = (Counter) field.get(source);
                    return counter == null ? 0 : counter.get();
                case TYPE_SEMAPHORE:
                    Semaphore semaphore = (Semaphore) field.get(source);
                    return semaphore == null ? 0 : semaphore.availablePermits();
                default:
                    throw new IllegalStateException("Unhandled type:" + type);
            }
        }
    }

    static class DoubleFieldProbe<S> extends FieldProbe implements DoubleProbeFunction<S> {

        DoubleFieldProbe(Field field, Probe probe, ProbeType type, SourceMetadata sourceMetadata) {
            super(field, probe, type, sourceMetadata);
        }

        @Override
        public double get(S source) throws Exception {
            switch (type) {
                case TYPE_DOUBLE_PRIMITIVE:
                    return field.getDouble(source);
                case TYPE_DOUBLE_NUMBER:
                    Number doubleNumber = (Number) field.get(source);
                    return doubleNumber == null ? 0 : doubleNumber.doubleValue();
                default:
                    throw new IllegalStateException("Unhandled type:" + type);
            }
        }
    }
}
