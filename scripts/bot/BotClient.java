import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraft.server.Packet;
import net.minecraft.server.Packet0KeepAlive;
import net.minecraft.server.Packet1Login;
import net.minecraft.server.Packet255KickDisconnect;
import net.minecraft.server.Packet2Handshake;
import net.minecraft.server.Packet51MapChunk;
import net.minecraft.server.Packet6SpawnPosition;

/**
 * Minimal Minecraft Beta 1.7.3 client used by scripts/smoke-test.sh and
 * scripts/soak.sh.
 *
 * It logs in over the real protocol, waits for chunks to arrive, optionally
 * stays connected for a while (answering keepalives like a real client) and
 * disconnects. That exercises the login, chunk streaming, keepalive and
 * disconnect paths of both networking stacks. Packet framing is delegated to
 * the server's own {@code net.minecraft.server.Packet} decoder, so the bot
 * cannot drift from the protocol the server actually speaks.
 *
 * Usage: BotClient [host] [port] [username] [chunks] [timeout-ms] [idle-ms] [keepalives]
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
        long idleMillis = Long.parseLong(arg(args, 5, "0"));
        int wantedKeepAlives = Integer.parseInt(arg(args, 6, "0"));

        long startNanos = System.nanoTime();

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
        long loginNanos = 0L;
        long chunksNanos = 0L;
        long chunksDoneNanos = 0L;
        Set<Long> uniqueChunks = new HashSet<Long>();

        while (System.currentTimeMillis() < deadline) {
            if (uniqueChunks.size() >= wantedChunks && keepAlives >= wantedKeepAlives) {
                if (idleMillis <= 0L || System.nanoTime() - chunksDoneNanos >= idleMillis * 1000000L) {
                    break;
                }
            }

            // Cap the read timeout so an idle wait wakes up to re-check the
            // deadline even when the server has nothing left to send.
            socket.setSoTimeout((int) Math.max(1L, Math.min(1000L, deadline - System.currentTimeMillis())));

            // false = packets the server may send to a client; true would only
            // accept client-to-server packets and reject id 6/51.
            Packet packet;
            try {
                packet = Packet.a(in, false, false);
            } catch (SocketTimeoutException ignored) {
                continue;
            }

            if (packet == null) {
                break;
            }

            if (packet instanceof Packet1Login) {
                Packet1Login login = (Packet1Login) packet;
                loggedIn = true;
                loginNanos = System.nanoTime() - startNanos;
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
                if (uniqueChunks.add(chunkKey(chunk.a, chunk.c)) && uniqueChunks.size() == wantedChunks) {
                    chunksNanos = System.nanoTime() - startNanos;
                    chunksDoneNanos = System.nanoTime();
                }
            } else if (packet instanceof Packet255KickDisconnect) {
                System.out.println("BOT kicked reason=" + ((Packet255KickDisconnect) packet).a);
                socket.close();
                System.exit(2);
            } else if (packet instanceof Packet0KeepAlive) {
                ++keepAlives;
                // A real client answers every keepalive.
                Packet.a(new Packet0KeepAlive(), out);
                out.flush();
            }
        }

        long totalNanos = System.nanoTime() - startNanos;

        System.out.println("BOT chunks=" + chunks + " unique_chunks=" + uniqueChunks.size()
                + " keepalives=" + keepAlives + " spawn=" + gotSpawn);
        System.out.println("BOT timing login_ms=" + millis(loginNanos)
                + " chunks_ms=" + millis(chunksNanos)
                + " total_ms=" + millis(totalNanos));
        socket.close();

        if (!loggedIn) {
            System.out.println("BOT FAILED: never received a login response");
            System.exit(1);
        }
        if (!gotSpawn) {
            System.out.println("BOT FAILED: never received a spawn position");
            System.exit(1);
        }
        if (uniqueChunks.size() < wantedChunks) {
            System.out.println("BOT FAILED: expected " + wantedChunks
                    + " distinct chunks, got " + uniqueChunks.size());
            System.exit(1);
        }
        if (keepAlives < wantedKeepAlives) {
            System.out.println("BOT FAILED: expected " + wantedKeepAlives
                    + " keepalives, got " + keepAlives);
            System.exit(1);
        }

        System.out.println("BOT PASSED");
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
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

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1000000.0D);
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
