import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class BookDatabase {
    private static final String DATABASE_NAME = "infante_library";
    private static final String TABLE_NAME = "books";
    private static String username = environmentValue("MYSQL_USER", "root");
    private static String password = environmentValue("MYSQL_PASSWORD", "");

    private BookDatabase() {
    }

    static void configureCredentials(String newUsername, String newPassword) {
        username = newUsername;
        password = newPassword;
    }

    static void initialize(Path legacyFile) throws SQLException, IOException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                    "MySQL Connector/J is missing. Build and run the project with Maven.", e);
        }

        try (Connection connection = openDatabaseConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " ("
                            + "book_id VARCHAR(255) NOT NULL PRIMARY KEY,"
                            + "category TEXT NOT NULL,"
                            + "title TEXT NOT NULL,"
                            + "author TEXT NOT NULL,"
                            + "borrowed BOOLEAN NOT NULL DEFAULT FALSE"
                            + ") CHARACTER SET utf8mb4");
            importLegacyBooksIfNeeded(connection, legacyFile);
        }
    }

    static ArrayList<Infante> loadBooks() throws SQLException {
        ArrayList<Infante> result = new ArrayList<>();
        String sql = "SELECT category, title, author, book_id, borrowed FROM " + TABLE_NAME
                + " ORDER BY book_id";
        try (Connection connection = openDatabaseConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.add(new Infante(
                        rows.getString("category"),
                        rows.getString("title"),
                        rows.getString("author"),
                        rows.getString("book_id"),
                        rows.getBoolean("borrowed")));
            }
        }
        return result;
    }

    static void addBook(Infante book) throws SQLException {
        String sql = "INSERT INTO " + TABLE_NAME
                + " (category, title, author, book_id, borrowed) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setBookFields(statement, book);
            statement.executeUpdate();
        }
    }

    static void updateBook(String originalId, Infante book) throws SQLException {
        String sql = "UPDATE " + TABLE_NAME
                + " SET category = ?, title = ?, author = ?, book_id = ?, borrowed = ?"
                + " WHERE book_id = ?";
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setBookFields(statement, book);
            statement.setString(6, originalId);
            statement.executeUpdate();
        }
    }

    static void setBorrowed(String id, boolean borrowed) throws SQLException {
        String sql = "UPDATE " + TABLE_NAME + " SET borrowed = ? WHERE book_id = ?";
        try (Connection connection = openDatabaseConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, borrowed);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    private static void setBookFields(PreparedStatement statement, Infante book)
            throws SQLException {
        statement.setString(1, book.category);
        statement.setString(2, book.title);
        statement.setString(3, book.author);
        statement.setString(4, book.id);
        statement.setBoolean(5, book.borrowed);
    }

    private static Connection openDatabaseConnection() throws SQLException {
        try {
            return DriverManager.getConnection(connectionUrl(DATABASE_NAME), username, password);
        } catch (SQLException e) {
            if (e.getErrorCode() != 1049) {
                throw e;
            }
        }

        try (Connection serverConnection = DriverManager.getConnection(connectionUrl(""), username,
                password);
                Statement statement = serverConnection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DATABASE_NAME
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        return DriverManager.getConnection(connectionUrl(DATABASE_NAME), username, password);
    }

    private static void importLegacyBooksIfNeeded(Connection connection, Path legacyFile)
            throws SQLException, IOException {
        if (!Files.isRegularFile(legacyFile)) {
            return;
        }

        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE_NAME)) {
            if (!rows.next() || rows.getLong(1) != 0) {
                return;
            }
        }

        List<Infante> legacyBooks = new ArrayList<>();
        List<String> lines = Files.readAllLines(legacyFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String[] fields = lines.get(i).split("/", -1);
            if (fields.length != 4 && fields.length != 5) {
                throw new IOException("Invalid book data on line " + (i + 1)
                        + " of " + legacyFile + ".");
            }
            boolean borrowed = fields.length == 5
                    && parseBorrowed(fields[4].trim(), i + 1, legacyFile);
            legacyBooks.add(new Infante(
                    fields[0].trim(),
                    fields[1].trim(),
                    fields[2].trim(),
                    fields[3].trim(),
                    borrowed));
        }

        String sql = "INSERT INTO " + TABLE_NAME
                + " (category, title, author, book_id, borrowed) VALUES (?, ?, ?, ?, ?)";
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Infante book : legacyBooks) {
                setBookFields(statement, book);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackError) {
                e.addSuppressed(rollbackError);
            }
            throw e;
        }
    }

    private static boolean parseBorrowed(String value, int lineNumber, Path file)
            throws IOException {
        if ("true".equalsIgnoreCase(value) || "borrowed".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "available".equalsIgnoreCase(value)
                || value.isEmpty()) {
            return false;
        }
        throw new IOException("Invalid book status on line " + lineNumber + " of " + file + ".");
    }

    private static String connectionUrl(String database) {
        String host = environmentValue("MYSQL_HOST", "localhost");
        String port = environmentValue("MYSQL_PORT", "3306");
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?serverTimezone=UTC";
    }

    private static String environmentValue(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
