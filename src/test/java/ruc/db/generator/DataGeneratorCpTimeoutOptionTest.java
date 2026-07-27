package ruc.db.generator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ruc.db.schema.Column;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataGeneratorCpTimeoutOptionTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(ConstructCpModel.CP_TIMEOUT_PROPERTY);
        System.clearProperty(Column.FILTER_EVAL_DIAGNOSTICS_PROPERTY);
    }

    @Test
    void configureCpTimeoutSecondsSetsConstructCpModelProperty() {
        DataGenerator.configureCpTimeoutSeconds(180.0);

        assertEquals("180.0", System.getProperty(ConstructCpModel.CP_TIMEOUT_PROPERTY));
    }

    @Test
    void configureCpTimeoutSecondsRejectsNegativeValue() {
        assertThrows(IllegalArgumentException.class,
                () -> DataGenerator.configureCpTimeoutSeconds(-1.0));
    }

    @Test
    void configureFilterEvalDiagnosticsSetsColumnProperty() {
        DataGenerator.configureFilterEvalDiagnostics(true);

        assertEquals("true", System.getProperty(Column.FILTER_EVAL_DIAGNOSTICS_PROPERTY));

        DataGenerator.configureFilterEvalDiagnostics(false);

        assertEquals("false", System.getProperty(Column.FILTER_EVAL_DIAGNOSTICS_PROPERTY));
    }
}
