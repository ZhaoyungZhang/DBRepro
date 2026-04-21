package ruc.db.dbconnector.adapter;

import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;

import java.sql.SQLException;

public class GaussConnector extends PgConnector{
    public GaussConnector(DatabaseConnectorConfig config) throws TouchstoneException, SQLException {
        super(config);
    }

    @Override
    protected String[] preExecutionCommands() {
        return new String[]{"SET join_collapse_limit = 1;"};
    }
}
