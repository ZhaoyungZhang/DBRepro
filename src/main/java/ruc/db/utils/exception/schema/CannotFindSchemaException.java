package ruc.db.utils.exception.schema;

import ruc.db.utils.exception.TouchstoneException;

/**
 * @author alan
 */
public class CannotFindSchemaException extends TouchstoneException {
    public CannotFindSchemaException(String tableName) {
        super(String.format("找不到表名'%s'对应的Schema", tableName));
    }
}
