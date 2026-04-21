package ruc.db.dbconnector;

import ruc.db.dbconnector.adapter.PgConnector;
import ruc.db.utils.DatabaseConnectorConfig;
import ruc.db.utils.exception.TouchstoneException;

import java.sql.SQLException;

/**
 * PostgreSQL连接器的简化包装类
 * 为RSGen CLI提供便捷的连接方式
 * 
 * @author RSGen Implementation
 */
public class PostgreSQLConnector extends PgConnector {
    
    public PostgreSQLConnector(String host, String port, String database, String username, String password) 
            throws TouchstoneException, SQLException {
        super(createConfig(host, port, database, username, password));
    }
    
    private static DatabaseConnectorConfig createConfig(String host, String port, String database, 
                                                       String username, String password) {
        return new DatabaseConnectorConfig(host, port, username, password, database);
    }
}
