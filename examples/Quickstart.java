import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.EppException;

import java.util.Arrays;
import java.util.Map;

/**
 * Minimal end-to-end example. Requires a live endpoint + credentials.
 *
 *     javac --release 8 -d out $(find src/main/java -name '*.java')
 *     javac --release 8 -cp out -d out examples/Quickstart.java
 *     EPP_CA=/path/to/registry-ca.pem java -cp out Quickstart
 */
public final class Quickstart {

    public static void main(String[] args) {
        String caFile = System.getenv("EPP_CA");

        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .port(700)          // default; override only if the endpoint moves
                // The language of the registry's result messages. Which ones it offers is in its
                // greeting, and a value it does not offer fails the login with 2102 - so read the
                // greeting rather than assuming a set.
                .lang("uk")
                .readTimeout(30.0)  // SECONDS (default 30; a read waits at least one second)
                // Needed only where the endpoint presents a certificate from the registry's own
                // private CA, which is in no trust store. Many registries present an ordinary
                // browser-trusted certificate, and then there is nothing to set here: with no caFile
                // the client trusts the JDK's own store. Ask your registry which applies.
                .caFile(caFile != null ? caFile : "/path/to/registry-ca.pem")
                .build();

        // Client is AutoCloseable and close() disconnects, so the socket is released on every way out
        // of this block, an exception included.
        try (Client client = new Client(config, null, new StderrLogger())) {
            client.connect();   // TLS + read <greeting>
            client.login();

            Map<String, Boolean> availability =
                    client.domain().check(Arrays.asList("example.com.ua")).availability();
            System.out.println("availability: " + availability);

            Response info = client.domain().info("example.com.ua");
            System.out.println("exDate: " + info.value("exDate"));

            System.out.println("balance: " + client.balance().balance());

            Response msg = client.poll().request();
            if (msg.messageId() != null) {
                // queueMessage() is the NOTICE text. message() is the result banner ("ack to
                // dequeue"), identical on every poll reply - printing that and then acking destroys
                // the unread notice at the registry for good.
                System.out.println("poll: " + msg.queueDate() + " " + msg.queueMessage());
                client.poll().ack(msg.messageId());
            }

            client.logout();
        } catch (CommandException e) {
            System.err.println("EPP error " + e.eppCode() + ": " + e.getMessage());
        } catch (EppException e) {
            System.err.println("SDK error: " + e.getMessage());
        }
    }

    /** Optional logger. Passwords and authInfo are masked before a frame reaches it. */
    private static final class StderrLogger implements Client.Logger {
        @Override
        public void debug(String message) {
            System.err.println("[debug] " + message);
        }

        @Override
        public void info(String message) {
            System.err.println("[info]  " + message);
        }

        @Override
        public void warning(String message) {
            System.err.println("[warn]  " + message);
        }
    }
}
