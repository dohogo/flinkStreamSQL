/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flink.sql.util;

import com.dtstack.flink.sql.side.AbstractSideTableInfo;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.RowTypeInfo;
import org.apache.flink.shaded.guava18.com.google.common.base.Splitter;
import org.apache.flink.table.api.Types;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.DecimalType;
import org.apache.flink.table.types.utils.TypeConversions;
import org.apache.flink.types.Row;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.apache.flink.table.api.DataTypes.DECIMAL;
import static org.apache.flink.table.api.DataTypes.TIMESTAMP;

/**
 * @program: flink.sql
 * @author: wuren
 * @create: 2020/07/09
 **/
public class DataTypeUtils {

    private final static Pattern COMPOSITE_TYPE_PATTERN = Pattern.compile("(.+?)<(.+)>");
    private final static String ARRAY = "ARRAY";
    private final static String ROW = "ROW";
    private final static char FIELD_DELIMITER = ',';
    private final static char TYPE_DELIMITER = ' ';

    private DataTypeUtils() {
    }

    /**
     * 现在只支持ARRAY类型后续可以加入 MAP等类型
     *
     * @param compositeTypeString
     * @return
     */
    public static TypeInformation convertToCompositeType(String compositeTypeString) {
        Matcher matcher = matchCompositeType(compositeTypeString);
        final String errorMsg = "type " + compositeTypeString + "is not support!";
        Preconditions.checkState(matcher.find(), errorMsg);

        String normalizedType = normalizeType(matcher.group(1));
        Preconditions.checkState(ARRAY.equals(normalizedType), errorMsg);

        return convertToArray(compositeTypeString);
    }

    /**
     * 目前ARRAY里只支持ROW和其他基本类型
     *
     * @param arrayTypeString
     * @return
     */
    public static TypeInformation convertToArray(String arrayTypeString) {
        Matcher matcher = matchCompositeType(arrayTypeString);
        final String errorMsg = arrayTypeString + "convert to array type error!";
        Preconditions.checkState(matcher.find(), errorMsg);

        String normalizedType = normalizeType(matcher.group(1));
        Preconditions.checkState(ARRAY.equals(normalizedType), errorMsg);

        String elementTypeString = matcher.group(2);
        TypeInformation elementType;
        String normalizedElementType = normalizeType(elementTypeString);
        if (normalizedElementType.startsWith(ROW)) {
            elementType = convertToRow(elementTypeString);
        } else {
            elementType = convertToAtomicType(elementTypeString);
        }

        return Types.OBJECT_ARRAY(elementType);
    }

    /**
     * 目前ROW里只支持基本类型
     *
     * @param rowTypeString
     */
    public static RowTypeInfo convertToRow(String rowTypeString) {
        Matcher matcher = matchCompositeType(rowTypeString);
        final String errorMsg = rowTypeString + "convert to row type error!";
        Preconditions.checkState(matcher.find(), errorMsg);

        String normalizedType = normalizeType(matcher.group(1));
        Preconditions.checkState(ROW.equals(normalizedType), errorMsg);

        String elementTypeStr = matcher.group(2);
        Iterable<String> fieldInfoStrs = splitCompositeTypeField(elementTypeStr);
        Tuple2<TypeInformation[], String[]> info = genFieldInfo(fieldInfoStrs);
        return new RowTypeInfo(info.f0, info.f1);
    }

    /**
     * class 转成 TypeInformation
     *
     * @param fieldTypes
     * @return
     */
    public static TypeInformation[] transformTypes(Class[] fieldTypes) {
        TypeInformation[] types = new TypeInformation[fieldTypes.length];
        for (int i = 0; i < fieldTypes.length; i++) {
            types[i] = TypeInformation.of(fieldTypes[i]);
        }

        return types;
    }

    /**
     * class 转成 TypeInformation<Row>
     *
     * @param fieldTypes
     * @param fieldClasses
     * @return
     */
    public static TypeInformation<Row> getRowTypeInformation(String[] fieldTypes, Class<?>[] fieldClasses) {
        TypeInformation[] types =
                IntStream.range(0, fieldClasses.length)
                        .mapToObj(i -> {
                            return TypeInformation.of(fieldClasses[i]);
                        })
                        .toArray(TypeInformation[]::new);


        return new RowTypeInfo(types, fieldTypes);
    }

    private static Tuple2<TypeInformation[], String[]> genFieldInfo(Iterable<String> fieldInfoStrs) {
        ArrayList<TypeInformation> types = Lists.newArrayList();
        ArrayList<String> fieldNames = Lists.newArrayList();

        for (String fieldStr : fieldInfoStrs) {
            Iterable<String> splitedInfo = splitTypeInfo(fieldStr);
            ArrayList<String> info = Lists.newArrayList(splitedInfo.iterator());
            Preconditions.checkState(info.size() == 2, "field info must be name with type");

            fieldNames.add(info.get(0));
            TypeInformation fieldType = convertToAtomicType(info.get(1));
            types.add(fieldType);
        }

        TypeInformation[] typeArray = types.toArray(new TypeInformation[types.size()]);
        String[] fieldNameArray = fieldNames.toArray(new String[fieldNames.size()]);
        return Tuple2.of(typeArray, fieldNameArray);
    }

    /**
     * 转换基本类型，所有类型参考Flink官方文档，一共12个基本类型。
     *
     * @param string
     * @return
     */
    public static TypeInformation convertToAtomicType(String string) {
        switch (normalizeType(string)) {
            case "VARCHAR":
            case "STRING":
                return Types.STRING();
            case "BOOLEAN":
                return Types.BOOLEAN();
            case "TINYINT":
                return Types.BYTE();
            case "SMALLINT":
                return Types.SHORT();
            case "INT":
            case "INTEGER":
                return Types.INT();
            case "BIGINT":
                return Types.LONG();
            case "FLOAT":
            case "REAL":
                return Types.FLOAT();
            case "DOUBLE":
                return Types.DOUBLE();
            case "DECIMAL":
            case "DEC":
            case "NUMERIC":
                return Types.DECIMAL();
            case "DATE":
                return Types.SQL_DATE();
            case "TIME":
                return Types.SQL_TIME();
            case "TIMESTAMP":
                return Types.SQL_TIMESTAMP();
            default:
                throw new RuntimeException("type " + string + "not supported, please refer to the flink doc!");
        }
    }

    /**
     * 获取维表物理字段名称对应的类型
     * 如果primaryKeys中的字段不在表字段中：如hbase的rowkey。则也需要将该字段加上默认string类型，否则join语法不通过
     *
     * @param sideTableInfo
     * @return
     */
    public static String[] getPhysicalFieldTypes(AbstractSideTableInfo sideTableInfo) {
        String[] fieldTypes = sideTableInfo.getFieldTypes();
        String[] fieldNames = getFieldNames(sideTableInfo);

        if (fieldNames.length == fieldTypes.length) {
            return fieldTypes;
        }

        String[] newFieldTypes = new String[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            if (i >= fieldTypes.length) {
                newFieldTypes[i] = "varchar";
                continue;
            }
            newFieldTypes[i] = fieldTypes[i];
        }
        return newFieldTypes;
    }

    /**
     * 获取维表物理字段名称
     * 如果primaryKeys中的字段不在表字段中：如hbase的rowkey。则也需要将该字段加上，否则join语法不通过
     *
     * @param sideTableInfo
     * @return
     */
    public static String[] getPhysicalFieldNames(AbstractSideTableInfo sideTableInfo) {
        String[] fieldNames = getFieldNames(sideTableInfo);
        Map<String, String> physicalFields = sideTableInfo.getPhysicalFields();
        List<String> columns = Stream.of(fieldNames)
                .map(x -> physicalFields.getOrDefault(x, x))
                .collect(Collectors.toList());

        String[] columnsArr = new String[columns.size()];
        return columns.toArray(columnsArr);
    }

    /**
     * 获取维表字段名称
     * 如果primaryKeys中的字段不在表字段中：如hbase的rowkey。则也需要将改字段加上，否则join语法不通过
     *
     * @param sideTableInfo
     * @return
     */
    public static String[] getFieldNames(AbstractSideTableInfo sideTableInfo) {
        List<String> fieldsList = new ArrayList<>(Arrays.asList(sideTableInfo.getFields()));
        Map<String, String> physicalFields = sideTableInfo.getPhysicalFields();

        sideTableInfo
                .getPrimaryKeys()
                .stream()
                .filter(p -> !physicalFields.containsKey(p) && !physicalFields.containsValue(p))
                .forEach(p -> fieldsList.add(p));

        return fieldsList.toArray(new String[fieldsList.size()]);
    }

    /**
     * 获取维表字段类型
     * java数据类型转换成DataType
     *
     * @param sideTableInfo
     * @return
     */
    public static DataType[] getFieldTypes(AbstractSideTableInfo sideTableInfo) {
        Class[] fieldClasses = completionFieldTypes(getFieldNames(sideTableInfo), sideTableInfo.getFieldClasses());
        DataType[] dataTypes = new DataType[fieldClasses.length];
        for (int i = 0; i < fieldClasses.length; i++) {
            if (fieldClasses[i].getName().equals(BigDecimal.class.getName())) {
                dataTypes[i] = DECIMAL(DecimalType.MAX_PRECISION, 18);
                continue;
            }
            if (fieldClasses[i].getName().equals(Timestamp.class.getName())) {
                dataTypes[i] = TIMESTAMP(3);
                continue;
            }
            dataTypes[i] = TypeConversions.fromClassToDataType(fieldClasses[i]).get();
        }
        return dataTypes;
    }

    /**
     * 补全primaryKey中存在，但是在fields不存在的字段的类型，默认为String
     *
     * @param fieldNames
     * @param fieldTypes
     * @return
     */
    private static Class[] completionFieldTypes(String[] fieldNames, Class[] fieldTypes) {
        if (fieldNames.length == fieldTypes.length) {
            return fieldTypes;
        }
        Class[] compFieldTypes = new Class[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            if (i >= fieldTypes.length) {
                compFieldTypes[i] = String.class;
                continue;
            }
            compFieldTypes[i] = fieldTypes[i];
        }
        return compFieldTypes;
    }

    private static Iterable<String> splitTypeInfo(String string) {
        return Splitter
                .on(TYPE_DELIMITER)
                .trimResults()
                .omitEmptyStrings()
                .split(string);
    }

    private static Iterable<String> splitCompositeTypeField(String string) {
        return Splitter
                .on(FIELD_DELIMITER)
                .trimResults()
                .split(string);
    }

    private static String replaceBlank(String s) {
        return s.replaceAll("\\s", " ").trim();
    }

    private static Matcher matchCompositeType(String s) {
        return COMPOSITE_TYPE_PATTERN.matcher(
                replaceBlank(s)
        );
    }

    private static String normalizeType(String s) {
        return s.toUpperCase().trim();
    }
}
