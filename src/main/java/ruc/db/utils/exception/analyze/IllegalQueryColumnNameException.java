package ruc.db.utils.exception.analyze;

import ruc.db.utils.exception.TouchstoneException;

public class IllegalQueryColumnNameException extends TouchstoneException {
    public IllegalQueryColumnNameException() {
        super("输入的列名不符合{database}.{table}.{column}的格式");
    }
}
