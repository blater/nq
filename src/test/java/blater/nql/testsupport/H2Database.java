package blater.nql.testsupport;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Map;
import java.util.UUID;

public class H2Database implements AutoCloseable {
    private final Connection connection;
    private final String url;

    public H2Database() throws SQLException {
        String databaseName = "hiql_" + UUID.randomUUID().toString().replace("-", "");
        url = "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
    }

    public Connection connection() {
        return connection;
    }

    public Map<String, String> jdbcProperties() {
        return Map.of(
            "jdbc.class.name", "org.h2.Driver",
            "jdbc.database", url,
            "jdbc.username", "sa",
            "jdbc.password", "");
    }

    public void execute(String... sqlStatements) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : sqlStatements) {
                statement.execute(sql);
            }
        }
    }

    public String queryString(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getString(1);
        }
    }

    public BigDecimal queryDecimal(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            return resultSet.getBigDecimal(1);
        }
    }

    public int queryInt(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
