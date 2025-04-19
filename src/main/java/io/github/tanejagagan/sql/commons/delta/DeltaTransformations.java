package io.github.tanejagagan.sql.commons.delta;

import com.fasterxml.jackson.databind.JsonNode;
import io.delta.kernel.annotation.Evolving;
import io.delta.kernel.expressions.*;
import io.delta.kernel.expressions.Column;
import io.delta.kernel.expressions.Literal;
import io.github.tanejagagan.sql.commons.Transformations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Arrays;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DeltaTransformations {
    // Define data type categories
    // https://docs.databricks.com/aws/en/sql/language-manual/sql-ref-datatypes#data-type-classification
    // NOTE intType -> INTEGER (NOT INT), binaryType -> BLOB (NOT BINARY)
    private static final String tinyIntType = "TINYINT";
    private static final String smallIntType = "SMALLINT";
    private static final String intType = "INTEGER";
    private static final String bigIntType = "BIGINT";
    private static final String decimalType = "DECIMAL";
    private static final String floatType = "FLOAT";
    private static final String doubleType = "DOUBLE";
    private static final String dateType = "DATE";
    private static final String timestampType = "TIMESTAMP";
    private static final String timestampNtzType = "TIMESTAMP_NTZ";
    private static final String arrayType = "ARRAY";
    private static final String mapType = "MAP";
    private static final String structType = "STRUCT";
    private static final String variantType = "VARIANT";
    private static final String objectType = "OBJECT";
    private static final String booleanType =  "BOOLEAN";
    private static final String binaryType = "BLOB";
    private static final String intervalType = "INTERVAL";
    private static final String stringType = "STRING";
    private static final List<String> integralNumericTypes = List.of(tinyIntType, smallIntType, intType, bigIntType);
    private static final List<String> exactNumericTypes = List.of(decimalType);
    private static final List<String> binaryFloatingPointTypes = List.of(floatType, doubleType);
    private static final List<String> numericTypes = Stream.of(integralNumericTypes, exactNumericTypes, binaryFloatingPointTypes).flatMap(List::stream).toList();
    private static final List<String> datetimeTypes = List.of(dateType, timestampType, timestampNtzType);
    private static final List<String> complexTypes =  List.of(arrayType, mapType, structType, variantType, objectType);

    private static final List<String> allTypes = Stream.of(
                    numericTypes.stream(),
                    datetimeTypes.stream(),
                    complexTypes.stream(),
                    Stream.of(booleanType),
                    Stream.of(binaryType),
                    Stream.of(intervalType),
                    Stream.of(stringType)
            )
            .flatMap(stream -> stream)
            .toList();

    // Define casting rules
    // https://docs.databricks.com/aws/en/sql/language-manual/functions/cast
    // TODO year-month interval, day-time interval
    private static final Map<String, List<String>> castRules = Map.ofEntries(
            // Numeric
            Map.entry(tinyIntType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(smallIntType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(intType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(bigIntType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(decimalType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(floatType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            Map.entry(doubleType, Stream.of(numericTypes.stream(), Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // String
            Map.entry(stringType, allTypes),
            // DATE
            Map.entry(dateType, Stream.of(Stream.of(stringType), datetimeTypes.stream(), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // TIMESTAMP
            Map.entry(timestampType, Stream.of(Stream.of(stringType), datetimeTypes.stream(), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // TIMESTAMP_NTZ
            Map.entry(timestampNtzType, Stream.of(Stream.of(stringType), datetimeTypes.stream(), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // BOOLEAN
            Map.entry(booleanType, Stream.of(Stream.of(stringType), Stream.of(timestampType), Stream.of(booleanType), numericTypes.stream(), Stream.of(variantType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // BINARY
            Map.entry(binaryType, Stream.of(Stream.of(stringType), numericTypes.stream(), Stream.of(variantType), Stream.of(binaryType)).flatMap(stream -> stream).collect(Collectors.toList())),
            // ARRAY
            Map.entry(arrayType, List.of(stringType, arrayType, arrayType, variantType)),
            // MAP
            Map.entry(mapType, List.of(stringType, mapType)),
            // STRUCT
            Map.entry(structType, List.of(stringType, structType)),
            // VARIANT
            Map.entry(variantType, List.of(stringType, variantType)),
            // OBJECT
            Map.entry(objectType, List.of(mapType, structType))
    );

    // TODO
    // 1. Binary Type
    // 2. Decimal Type
    // 3. dateType, timestampType and timestampNtzType
    // 4. ArrayType, MapType, StructType, StructField
    // https://github.com/apache/spark/blob/87b9866903baa3b291058b3613f9954ec62c178c/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/Cast.scala#L4
    private static final Map<String, Function<Expression, Object>> parsingFunctions = Map.ofEntries(
            Map.entry(tinyIntType, expr -> Byte.parseByte(expr.toString())),
            Map.entry(smallIntType, expr -> Short.parseShort(expr.toString())),
            Map.entry(intType, expr -> Integer.parseInt(expr.toString())),
            Map.entry(bigIntType, expr -> Long.parseLong(expr.toString())),
            Map.entry(floatType, expr -> Float.parseFloat(expr.toString())),
            Map.entry(doubleType, expr -> Double.parseDouble(expr.toString())),
//          Map.entry(decimalType),
            Map.entry(stringType, expr -> (expr.toString())),
            Map.entry(binaryType, expr -> {
                // Assuming Expression stores a byte[] as its value
                if (expr instanceof Literal) {
                    return ((Literal) expr).getDataType(); // Replace this with the actual method
                }
                throw new IllegalArgumentException("Invalid expression type for BINARY: " + expr);
            }),
            Map.entry(booleanType, expr -> Boolean.parseBoolean(expr.toString())),
            Map.entry(timestampType, expr -> {
                return java.sql.Timestamp.valueOf(expr.toString()).getTime() * 1000; // Convert to microseconds
            }),
            Map.entry(timestampNtzType, expr -> java.time.LocalDateTime.parse(expr.toString())),
            Map.entry(dateType, expr -> Date.parse(expr.toString()))

    );

    private static final Map<String, Function<Object, Expression>> castingFunctions = Map.ofEntries(
            Map.entry(tinyIntType, value -> Literal.ofByte(((Number) value).byteValue())),
            Map.entry(smallIntType, value -> Literal.ofShort(((Number) value).shortValue())),
            Map.entry(intType, value -> Literal.ofInt(((Number) value).intValue())),
            Map.entry(bigIntType, value -> Literal.ofLong(((Number) value).longValue())),
            Map.entry(floatType, value -> Literal.ofFloat(((Number) value).floatValue())),
            Map.entry(doubleType, value -> Literal.ofDouble(((Number) value).doubleValue())),
//          Map.entry(decimalType),
            Map.entry(stringType, value -> Literal.ofString(value.toString())),
            Map.entry(binaryType, value -> Literal.ofBinary((byte[]) value)),
            Map.entry(booleanType, value -> Literal.ofBoolean((Boolean) value)),
//            Map.entry(timestampType, value -> {
//            })
            Map.entry(dateType, value -> Literal.ofDate(Integer.parseInt(value.toString())))
    );

    /**
     * Perform a two-step cast: parse the source type and then cast to the target type.
     *
     * @param expr       The expression to cast.
     * @param sourceType The source type of the expression (e.g., TINYINT).
     * @param targetType The target type to cast to (e.g., INT).
     * @return The casted expression.
     */
    private static Expression castExpression(Expression expr, String sourceType, String targetType) {
        // Step 1: Parse the source type
        Function<Expression, Object> parseFunction = parsingFunctions.get(sourceType);
        if (parseFunction == null) {
            throw new UnsupportedOperationException("Unsupported source type: " + sourceType);
        }
        Object intermediateValue = parseFunction.apply(expr);

        // Step 2: Cast to the target type
        Function<Object, Expression> castFunction = castingFunctions.get(targetType);
        if (castFunction == null) {
            throw new UnsupportedOperationException("Unsupported target type: " + targetType);
        }
        return castFunction.apply(intermediateValue);
    }





//    // Define casting functions
//    private static final Map<String, Function<Expression, Expression>> castingFunctions = Map.ofEntries(
//            Map.entry("BOOLEAN", expr -> Literal.ofBoolean(Boolean.parseBoolean(expr.toString()))),
//            Map.entry("BYTE", expr -> Literal.ofByte(Byte.parseByte(expr.toString()))),
//            Map.entry("SHORT", expr -> Literal.ofShort(Short.parseShort(expr.toString()))),
//            Map.entry("INTEGER", expr -> Literal.ofInt(Integer.parseInt(expr.toString()))),
//            Map.entry("VARCHAR", expr -> Literal.ofString(expr.toString())),
//            Map.entry("LONG", expr -> Literal.ofLong(Long.parseLong(expr.toString()))),
//            Map.entry("FLOAT", expr -> Literal.ofFloat(Float.parseFloat(expr.toString()))),
//            Map.entry("DOUBLE", expr -> Literal.ofDouble(Double.parseDouble(expr.toString()))),
//            // TODO
////            Map.entry("DECIMAL", expr -> {
////
////            }),
//            Map.entry("STRING", expr -> Literal.ofString(expr.toString())),
//            Map.entry("TIMESTAMP", expr -> Literal.ofTimestamp(timestampToMicrosSinceEpochUTC(expr.toString()))),
//            Map.entry("TIMESTAMP_NTZ", expr -> Literal.ofTimestamp(timestampToMicrosSinceEpochUTC(expr.toString())))
//    );

    // Function to check if a type can be cast to another type
    private static boolean canCast(String fromType, String toType) {
        return castRules.get(fromType).contains(toType);
    }

    /**
     * Converts a string timestamp to microseconds since UTC epoch.
     *
     * @param value The timestamp string value
     * @param zoneId The time zone to use when timestamp has no zone
     *        If null, treats timestamp as UTC
     * @return microseconds since UTC epoch, or null if conversion failed
     */
    public static Long timestampToMicrosSinceEpochUTC(String value, ZoneId zoneId) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // Default to UTC if zoneId is null
        ZoneId effectiveZoneId = (zoneId != null) ? zoneId : ZoneOffset.UTC;

        // Try parsing as a number first
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // Not a number, continue to date parsing
        }

        try {
            // First check if the string contains timezone information (Z, +, -)
            if (value.endsWith("Z") || value.contains("+") ||
                    (value.contains("-") && value.lastIndexOf("-") > value.indexOf("-") + 2)) {

                // Parse as a timestamp with timezone
                Instant instant;
                try {
                    if (value.contains("T")) {
                        // ISO-8601 format
                        instant = Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
                    } else {
                        // Try with custom formatter
                        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                                .appendPattern("yyyy-MM-dd HH:mm:ss")
                                .optionalStart()
                                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                                .optionalEnd()
                                .appendOffset("+HH:MM", "Z")
                                .toFormatter();
                        instant = Instant.from(formatter.parse(value));
                    }
                } catch (Exception e) {
                    // Try with OffsetDateTime as a fallback
                    instant = OffsetDateTime.parse(value).toInstant();
                }

                return instant.toEpochMilli() * 1000;

            } else {
                // Parse as a LocalDateTime (no timezone info)
                LocalDateTime localDateTime;

                if (value.contains("T")) {
                    // ISO-8601-like format without timezone
                    localDateTime = LocalDateTime.parse(value);
                } else if (value.contains("-") && value.contains(":")) {
                    // "YYYY-MM-DD HH:MM:SS[.fffffffff]" format
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS]");
                    localDateTime = LocalDateTime.parse(value, formatter);
                } else if (value.contains("-") && !value.contains(":")) {
                    // Date only format (yyyy-MM-dd)
                    localDateTime = LocalDate.parse(value).atStartOfDay();
                } else {
                    // Try with flexible formatter as last resort
                    DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                            .appendPattern("yyyy-MM-dd[ HH:mm:ss]")
                            .optionalStart()
                            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
                            .optionalEnd()
                            .toFormatter();
                    localDateTime = LocalDateTime.parse(value, formatter);
                }

                // Convert to epoch microseconds using the specified timezone
                ZonedDateTime zonedDateTime = localDateTime.atZone(effectiveZoneId);
                return zonedDateTime.toInstant().toEpochMilli() * 1000;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Overloaded method with default UTC timezone.
     */
    public static Long timestampToMicrosSinceEpochUTC(String value) {
        return timestampToMicrosSinceEpochUTC(value, ZoneOffset.UTC);
    }

    @Evolving
    public static final class Equal extends Predicate {
        public Equal(Expression left, Expression right) {
            super("=", Arrays.asList(left, right));
        }
    }

    @Evolving
    public static final class NotEqual extends Predicate {
        public NotEqual(Expression left, Expression right) {
            super("NOT", Arrays.asList(new Equal(left, right)));
        }
    }

    @Evolving
    public static final class GreaterThan extends Predicate {
        public GreaterThan(Expression left, Expression right) {
            super(">", Arrays.asList(left, right));
        }
    }

    @Evolving
    public static final class GreaterThanOrEqualTo extends Predicate {
        public GreaterThanOrEqualTo(Expression left, Expression right) {
            super(">=", Arrays.asList(left, right));
        }
    }

    @Evolving
    public static final class LessThan extends Predicate {
        public LessThan(Expression left, Expression right) {
            super("<", Arrays.asList(left, right));
        }
    }

    @Evolving
    public static final class CompareLessThanOrEqualTo extends Predicate {
        public CompareLessThanOrEqualTo(Expression left, Expression right) {
            super("<=", Arrays.asList(left, right));
        }
    }

    public static Expression toDeltaPredicate(JsonNode jsonPredicate) throws IOException {
        if(Transformations.IS_CONSTANT.apply(jsonPredicate)) {
            return toLiteral(jsonPredicate);
        } else if (Transformations.IS_REFERENCE.apply(jsonPredicate)) {
            return toReference(jsonPredicate);
        } else if(Transformations.IS_COMPARISON.apply(jsonPredicate)){
            return toComparison(jsonPredicate);
        } else if(Transformations.IS_CONJUNCTION_AND.apply(jsonPredicate) || Transformations.IS_CONJUNCTION_OR.apply(jsonPredicate)  ) {
            return toConjunction(jsonPredicate);
        } else if(Transformations.IS_CAST.apply(jsonPredicate)) {
            return toCast(jsonPredicate);
        }
        throw new UnsupportedOperationException("No transformation supported" + jsonPredicate);
    }

    private static Expression toComparison(JsonNode comparison) throws IOException {
        JsonNode left = comparison.get("left");
        JsonNode right = comparison.get("right");
        Expression leftExpr = toDeltaPredicate(left);
        Expression rightExpr = toDeltaPredicate(right);

        String comparisonType = comparison.get("type").asText();
        return switch (comparisonType) {
            case "COMPARE_EQUAL" -> new Equal(leftExpr, rightExpr);
            case "COMPARE_NOT_EQUAL" -> new NotEqual(leftExpr, rightExpr);
            case "COMPARE_GREATER_THAN" -> new GreaterThan(leftExpr, rightExpr);
            case "COMPARE_GREATERTHANOREQUALTO" -> new GreaterThanOrEqualTo(leftExpr, rightExpr);
            case "COMPARE_LESS_THAN" -> new LessThan(leftExpr, rightExpr);
            case "COMPARE_LESSTHANOREQUALTO"-> new CompareLessThanOrEqualTo(leftExpr, rightExpr);
            default -> throw new UnsupportedOperationException("Unsupported comparison type: " + comparisonType);
        };
    }

    private static Expression toConjunction(JsonNode conjunction) throws IOException {
        JsonNode children = conjunction.get("children");
        String conjunctionType = conjunction.get("type").asText();

        if (children.size() != 2) {
            throw new UnsupportedOperationException("Only binary conjunctions are supported");
        }

        Predicate leftPredicate = (Predicate) toDeltaPredicate(children.get(0));
        Predicate rightPredicate = (Predicate) toDeltaPredicate(children.get(1));

        if ("CONJUNCTION_AND".equals(conjunctionType)) {
            return new And(leftPredicate, rightPredicate);
        } else if ("CONJUNCTION_OR".equals(conjunctionType)) {
            return new Or(leftPredicate, rightPredicate);
        } else {
            throw new UnsupportedOperationException("Unsupported conjunction type: " + conjunctionType);
        }
    }

    // TODO
    private static Expression toLiteral(JsonNode literal) throws IOException {
        JsonNode value = literal.get("value");
        String type = value.get("type").get("id").asText();

        return switch (type) {
            case "INTEGER" -> Literal.ofInt(value.get("value").asInt());
            case "VARCHAR" -> Literal.ofString(value.get("value").asText());
            case "BOOLEAN" -> Literal.ofBoolean(value.get("value").asBoolean());
            case "DATE" -> Literal.ofDate(Integer.parseInt(value.get("value").toString()));
            case "TIMESTAMP" ->
                    Literal.ofTimestamp(Long.parseLong(value.get("value").toString()));
            case "FLOAT" -> Literal.ofFloat((float) value.get("value").asDouble());
            case "DOUBLE" -> Literal.ofDouble(value.get("value").asDouble());
            case "BLOB" -> Literal.ofBinary(value.get("value").textValue().getBytes(StandardCharsets.UTF_8));
            default -> throw new UnsupportedOperationException("Unsupported literal type: " + type);
        };
    }

    private static Expression toReference(JsonNode reference) {
        String columnName = reference.get("column_names").get(0).asText();
        return new Column(columnName);
    }

//    private static Expression castExpression(Expression expr, String sourceType, String targetType) {
//        if (castingFunctions.containsKey(targetType)) {
//            return castingFunctions.get(targetType).apply(expr);
//        } else {
//            throw new UnsupportedOperationException("Unsupported literal type: " + targetType);
//        }
//    }

    private static Expression toCast(JsonNode cast) throws IOException {
        System.out.println(cast.toPrettyString());
        JsonNode child = cast.get("child");
        Expression childExpr = toDeltaPredicate(child);
        String sourceType = String.valueOf(((Literal) childExpr).getDataType()).toUpperCase();;
        String castType = cast.get("cast_type").get("id").asText().toUpperCase();;

        if (!canCast(sourceType, castType)) {
            throw new UnsupportedOperationException("Casting from " + sourceType + " to " + castType + " is not supported");
        }
        return castExpression(childExpr, sourceType, castType);
    }
}