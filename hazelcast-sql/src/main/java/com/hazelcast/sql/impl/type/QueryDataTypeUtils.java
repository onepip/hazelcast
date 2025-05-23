/*
 * Copyright 2025 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.type;

import com.hazelcast.sql.impl.type.converter.Converter;
import com.hazelcast.sql.impl.type.converter.Converters;

import java.math.MathContext;
import java.math.RoundingMode;

import static com.hazelcast.sql.impl.type.QueryDataType.BIGINT;
import static com.hazelcast.sql.impl.type.QueryDataType.BOOLEAN;
import static com.hazelcast.sql.impl.type.QueryDataType.DATE;
import static com.hazelcast.sql.impl.type.QueryDataType.DECIMAL;
import static com.hazelcast.sql.impl.type.QueryDataType.DOUBLE;
import static com.hazelcast.sql.impl.type.QueryDataType.INT;
import static com.hazelcast.sql.impl.type.QueryDataType.JSON;
import static com.hazelcast.sql.impl.type.QueryDataType.MAX_DECIMAL_PRECISION;
import static com.hazelcast.sql.impl.type.QueryDataType.NULL;
import static com.hazelcast.sql.impl.type.QueryDataType.OBJECT;
import static com.hazelcast.sql.impl.type.QueryDataType.REAL;
import static com.hazelcast.sql.impl.type.QueryDataType.ROW;
import static com.hazelcast.sql.impl.type.QueryDataType.SMALLINT;
import static com.hazelcast.sql.impl.type.QueryDataType.TIME;
import static com.hazelcast.sql.impl.type.QueryDataType.TIMESTAMP;
import static com.hazelcast.sql.impl.type.QueryDataType.TIMESTAMP_WITH_TZ_OFFSET_DATE_TIME;
import static com.hazelcast.sql.impl.type.QueryDataType.TINYINT;
import static com.hazelcast.sql.impl.type.QueryDataType.VARCHAR;

/**
 * Utility methods for SQL data types.
 * <p>
 * Length descriptions are generated using
 * <a href="https://github.com/openjdk/jol">Java Object Layout (JOL)</a>.
 */
public final class QueryDataTypeUtils {
    /**
     * java.lang.String footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        32        32   [C
     *     1        24        24   java.lang.String
     *     2                  56   (total)
     */
    public static final int TYPE_LEN_VARCHAR = 56;

    /**
     * java.math.BigDecimal footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        32        32   [I
     *     1        40        40   java.math.BigDecimal
     *     1        40        40   java.math.BigInteger
     *     3                 112   (total)
     */
    public static final int TYPE_LEN_DECIMAL = 112;

    /**
     * java.time.LocalTime footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        24        24   java.time.LocalTime
     *     1                  24   (total)
     */
    public static final int TYPE_LEN_TIME = 24;

    /**
     * java.time.LocalDate footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        24        24   java.time.LocalDate
     *     1                  24   (total)
     */
    public static final int TYPE_LEN_DATE = 24;

    /**
     * java.time.LocalDateTime footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        24        24   java.time.LocalDate
     *     1        24        24   java.time.LocalDateTime
     *     1        24        24   java.time.LocalTime
     *     3                  72   (total)
     */
    public static final int TYPE_LEN_TIMESTAMP = 72;

    /**
     * java.time.OffsetDateTime footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        24        24   [C
     *     1        24        24   java.lang.String
     *     1        24        24   java.time.LocalDate
     *     1        24        24   java.time.LocalDateTime
     *     1        24        24   java.time.LocalTime
     *     1        24        24   java.time.OffsetDateTime
     *     1        24        24   java.time.ZoneOffset
     *     7                 168   (total)
     */
    public static final int TYPE_LEN_TIMESTAMP_WITH_TIME_ZONE = 168;

    /**
     * com.hazelcast.sql.impl.type.SqlYearMonthInterval footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        16        16   com.hazelcast.sql.impl.type.SqlYearMonthInterval
     *     1                  16   (total)
     */
    public static final int TYPE_LEN_INTERVAL_YEAR_MONTH = 16;

    /**
     * com.hazelcast.sql.impl.type.SqlDaySecondInterval footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        24        24   com.hazelcast.sql.impl.type.SqlDaySecondInterval
     *     1                  24   (total)
     */
    public static final int TYPE_LEN_INTERVAL_DAY_SECOND = 24;

    /**
     * java.util.HashMap footprint:
     * COUNT       AVG       SUM   DESCRIPTION
     *     1        48        48   java.util.HashMap
     *     1                  48   (total)
     *
     * Empty map.
     */
    public static final int TYPE_LEN_MAP = 48;

    /**
     * TODO: replace footprint with actual value?
     *
     * True VARCHAR footprint is 40, old values are kept for now, therefore new value is computed
     * as VARCHAR footprint + 16 (reference/class footprint).
     */
    public static final int TYPE_LEN_JSON = TYPE_LEN_VARCHAR + 16;

    /** 12 (hdr) + 36 (arbitrary content). */
    public static final int TYPE_LEN_OBJECT = 12 + 36;

    /** TODO: actual value? */
    public static final int TYPE_LEN_ROW = 12 + 36;

    // With a non-zero value we avoid weird zero-cost columns. Technically, it
    // still costs a single reference now, but reference cost is not taken into
    // account as of now.
    public static final int TYPE_LEN_NULL = 1;

    public static final int PRECEDENCE_NULL = 0;
    public static final int PRECEDENCE_VARCHAR = 100;
    public static final int PRECEDENCE_BOOLEAN = 200;
    public static final int PRECEDENCE_TINYINT = 300;
    public static final int PRECEDENCE_SMALLINT = 400;
    public static final int PRECEDENCE_INTEGER = 500;
    public static final int PRECEDENCE_BIGINT = 600;
    public static final int PRECEDENCE_DECIMAL = 700;
    public static final int PRECEDENCE_REAL = 800;
    public static final int PRECEDENCE_DOUBLE = 900;
    public static final int PRECEDENCE_TIME = 1000;
    public static final int PRECEDENCE_DATE = 1100;
    public static final int PRECEDENCE_TIMESTAMP = 1200;
    public static final int PRECEDENCE_TIMESTAMP_WITH_TIME_ZONE = 1300;
    public static final int PRECEDENCE_OBJECT = 1400;
    public static final int PRECEDENCE_INTERVAL_YEAR_MONTH = 10;
    public static final int PRECEDENCE_INTERVAL_DAY_SECOND = 20;
    public static final int PRECEDENCE_MAP = 30;
    public static final int PRECEDENCE_JSON = 40;
    public static final int PRECEDENCE_ROW = 50;

    /**
     * Math context used by expressions while doing math on BigDecimal values.
     * <p>
     * The context uses {@link RoundingMode#HALF_UP HALF_UP} rounding mode, with
     * which most users should be familiar from school, and limits the precision
     * to {@link QueryDataType#MAX_DECIMAL_PRECISION}.
     */
    public static final MathContext DECIMAL_MATH_CONTEXT = new MathContext(MAX_DECIMAL_PRECISION, RoundingMode.HALF_UP);

    private QueryDataTypeUtils() { }

    public static QueryDataType resolveTypeForClass(Class<?> clazz) {
        Converter converter = Converters.getConverter(clazz);
        QueryDataType type = QueryDataType.resolveForConverter(converter);
        if (type == null) {
            throw new IllegalArgumentException("Unexpected class: " + clazz);
        }
        return type;
    }

    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:ReturnCount", "checkstyle:MethodLength"})
    public static QueryDataType resolveTypeForTypeFamily(QueryDataTypeFamily typeFamily) {
        switch (typeFamily) {
            case VARCHAR:
                return VARCHAR;

            case BOOLEAN:
                return BOOLEAN;

            case TINYINT:
                return TINYINT;

            case SMALLINT:
                return SMALLINT;

            case INTEGER:
                return INT;

            case BIGINT:
                return BIGINT;

            case DECIMAL:
                return DECIMAL;

            case REAL:
                return REAL;

            case DOUBLE:
                return DOUBLE;

            case DATE:
                return DATE;

            case TIME:
                return TIME;

            case TIMESTAMP:
                return TIMESTAMP;

            case TIMESTAMP_WITH_TIME_ZONE:
                return TIMESTAMP_WITH_TZ_OFFSET_DATE_TIME;

            case OBJECT:
                return OBJECT;

            case NULL:
                return NULL;

            case JSON:
                return JSON;

            case ROW:
                return ROW;

            default:
                throw new IllegalArgumentException("Unexpected type family: " + typeFamily);
        }
    }

    public static boolean isNumeric(QueryDataType type) {
        return isNumeric(type.getTypeFamily());
    }

    public static boolean isNumeric(QueryDataTypeFamily typeFamily) {
        switch (typeFamily) {
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case REAL:
            case DOUBLE:
                return true;

            default:
                return false;
        }
    }
}
