import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Verifies that the shaded server jar ships usable JDBC drivers.
 *
 * Canyon bundles sqlite-jdbc and MySQL Connector/J. A version bump can
 * silently break either one (wrong bytecode level, renamed driver class,
 * missing native library), so the smoke test loads both drivers and runs
 * a real in-memory SQLite round trip.
 */
public class DriverCheck {

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Class.forName("com.mysql.cj.jdbc.Driver");
        // Older bukkit.yml files name this class; Connector/J keeps it as a shim.
        Class.forName("com.mysql.jdbc.Driver");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("create table smoke (id integer primary key, name text)");
            statement.execute("insert into smoke (name) values ('canyon')");

            try (ResultSet result = statement.executeQuery("select count(*) from smoke")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalStateException("SQLite round trip returned no row");
                }
            }
        }

        System.out.println("DRIVER CHECK PASSED");
    }
}
