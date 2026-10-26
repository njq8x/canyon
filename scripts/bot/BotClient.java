import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

import net.minecraft.server.Packet;
import net.minecraft.server.Packet0KeepAlive;
import net.minecraft.server.Packet1Login;
import net.minecraft.server.Packet255KickDisconnect;
import net.minecraft.server.Packet2Handshake;
import net.minecraft.server.Packet51MapChunk;
import net.minecraft.server.Packet6SpawnPosition;

/**
 * Minimal Minecraft Beta 1.7.3 client used by scripts/smoke-test.sh.
 *
 * It logs in over the real protocol, waits for chunks to arrive and
 * disconnects, which exercises the login, chunk streaming and disconnect paths
 * of both networking stacks. Packet framing is delegated to the server's own
 * {@code net.minecraft.server.Packet} decoder, so the bot cannot drift from
 * the protocol the server actually speaks.
 *
 * Usage: BotClient [host] [port] [username] [chunks] [timeout-ms]
 */
public final class BotClient {

    private static final int PROTOCOL_VERSION = 14;

    private BotClient() {
    }

    public static void main(String[] args) throws Exception {
        String host = arg(args, 0, "127.0.0.1");
        int port = Integer.parseInt(arg(args, 1, "25565"));
        String username = arg(args, 2, "SmokeBot");
        int wantedChunks = Integer.parseInt(arg(args, 3, "10"));
        long timeoutMillis = Long.parseLong(arg(args, 4, "15000"));

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10000);
        socket.setSoTimeout((int) Math.min(timeoutMillis, Integer.MAX_VALUE));

        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        long deadline = System.currentTimeMillis() + timeoutMillis;

        // Handshake, then wait for the server's hash before logging in.
        Packet.a(new Packet2Handshake(username), out);
        out.flush();

        if (!waitForHandshake(in, deadline)) {
            fail(socket, "no handshake response from the server");
        }

        Packet.a(new Packet1Login(username, PROTOCOL_VERSION, 0L, (byte) 0), out);
        out.flush();

        boolean loggedIn = false;
        boolean gotSpawn = false;
        int chunks = 0;
        int keepAlives = 0;

        while (System.currentTimeMillis() < deadline && chunks < wantedChunks) {
            // false = packets the server may send to a client; true would only
            // accept client-to-server packets and reject id 6/51.
            Packet packet = Packet.a(in, false, false);

            if (packet == null) {
                break;
            }

            if (packet instanceof Packet1Login) {
                Packet1Login login = (Packet1Login) packet;
                loggedIn = true;
                System.out.println("BOT login entity_id=" + login.a + " dimension=" + login.d);
            } else if (packet instanceof Packet6SpawnPosition) {
                Packet6SpawnPosition spawn = (Packet6SpawnPosition) packet;
                gotSpawn = true;
                System.out.println("BOT spawn x=" + spawn.x + " y=" + spawn.y + " z=" + spawn.z);
            } else if (packet instanceof Packet51MapChunk) {
                Packet51MapChunk chunk = (Packet51MapChunk) packet;
                if (chunk.g == null || chunk.g.length == 0) {
                    fail(socket, "received an empty map chunk");
                }
                ++chunks;
            } else if (packet instanceof Packet255KickDisconnect) {
                System.out.println("BOT kicked reason=" + ((Packet255KickDisconnect) packet).a);
                socket.close();
                System.exit(2);
            } else if (packet instanceof Packet0KeepAlive) {
                ++keepAlives;
            }
        }

        System.out.println("BOT chunks=" + chunks + " keepalives=" + keepAlives + " spawn=" + gotSpawn);
        socket.close();

        if (!loggedIn) {
            System.out.println("BOT FAILED: never received a login response");
            System.exit(1);
        }
        if (!gotSpawn) {
            System.out.println("BOT FAILED: never received a spawn position");
            System.exit(1);
        }
        if (chunks < wantedChunks) {
            System.out.println("BOT FAILED: expected " + wantedChunks + " chunks, got " + chunks);
            System.exit(1);
        }

        System.out.println("BOT PASSED");
    }

    private static boolean waitForHandshake(DataInputStream in, long deadline) throws Exception {
        while (System.currentTimeMillis() < deadline) {
            Packet packet = Packet.a(in, false, false);

            if (packet == null) {
                return false;
            }
            if (packet instanceof Packet2Handshake) {
                return true;
            }
            if (packet instanceof Packet255KickDisconnect) {
                System.out.println("BOT kicked during login: " + ((Packet255KickDisconnect) packet).a);
                return false;
            }
        }
        return false;
    }

    private static void fail(Socket socket, String message) {
        System.out.println("BOT FAILED: " + message);
        try {
            socket.close();
        } catch (Exception ignored) {
            // best effort
        }
        System.exit(1);
    }

    private static String arg(String[] args, int index, String fallback) {
        return index < args.length ? args[index] : fallback;
    }
}
