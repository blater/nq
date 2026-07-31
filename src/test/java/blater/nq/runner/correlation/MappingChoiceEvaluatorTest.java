package blater.nq.runner.correlation;

import blater.nq.domain.Evaluator;
import blater.nq.runner.sql.domain.QueryResultRow;
import blater.nq.domain.MappingCondition;
import blater.nq.domain.Operator;
import blater.nq.domain.SqlType;
import blater.nq.domain.DateFormats;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MappingChoiceEvaluatorTest {
    @Test
    public void evaluatesStringEquality() {
        QueryResultRow row = stringRow(values("status", "ACTIVE"), Map.of());

        assertTrue(Evaluator.evaluate(condition("status", Operator.EQ, "ACTIVE", SqlType.STRING), row));
        assertFalse(Evaluator.evaluate(condition("status", Operator.EQ, "INACTIVE", SqlType.STRING), row));
    }

    @Test
    public void evaluatesEqualityAcrossTypeConversions() {
        QueryResultRow row = TestCurrentQueryRows.typedRow(
            Map.of(
                "age",        42,
                "long_col",   10_000_000_000L,
                "short_col",  (short) 12,
                "float_col",  1.5f,
                "double_col", 2.5d),
            Map.of(),
            Map.of());

        assertTrue(Evaluator.evaluate(condition("age", Operator.EQ, 42, SqlType.INTEGER), row));
        assertTrue(Evaluator.evaluate(condition("long_col", Operator.EQ, 10_000_000_000L, SqlType.LONG), row));
        assertTrue(Evaluator.evaluate(condition("short_col", Operator.EQ, (short) 12, SqlType.SHORT), row));
        assertTrue(Evaluator.evaluate(condition("float_col", Operator.EQ, 1.5f, SqlType.FLOAT), row));
        assertTrue(Evaluator.evaluate(condition("double_col", Operator.EQ, 2.5d, SqlType.DOUBLE), row));
    }

    @Test
    public void evaluatesDateEquality() {
        LocalDateTime created = LocalDateTime.parse("2-Jan-2024 00:00:00.000", DateFormats.TIMESTAMP);
        QueryResultRow row = TestCurrentQueryRows.typedRow(Map.of("created", created), Map.of(), Map.of());

        assertTrue(Evaluator.evaluate(condition("created", Operator.EQ,
            LocalDateTime.parse("2-Jan-2024 00:00:00.000", DateFormats.TIMESTAMP), SqlType.DATE), row));
    }

    @Test
    public void delegatesNewValueOperatorToRow() {
        QueryResultRow row = stringRow(values("personid", "10"), Map.of("personid", true));

        assertTrue(Evaluator.evaluate(MappingCondition.newValue("personid"), row));
        assertFalse(Evaluator.evaluate(MappingCondition.newValue("nicknameid"), row));
    }

    @Test
    public void missingStringColumnReadsAsNullAndDoesNotMatch() {
        QueryResultRow row = stringRow(Map.of(), Map.of());

        assertFalse(Evaluator.evaluate(condition("missing", Operator.EQ, "", SqlType.STRING), row));
        assertFalse(Evaluator.evaluate(condition("missing", Operator.EQ, "value", SqlType.STRING), row));
    }


    private MappingCondition condition(
        String fieldName,
        Operator operator,
        Object expected,
        SqlType sqlType) {
        return new MappingCondition(fieldName, operator, expected, sqlType);
    }

    private QueryResultRow stringRow(Map<String, String> values, Map<String, Boolean> newValues) {
        return TestCurrentQueryRows.row(values, newValues, Map.of());
    }

    private Map<String, String> values(String... pairs) {
        return TestCurrentQueryRows.values(pairs);
    }
}
