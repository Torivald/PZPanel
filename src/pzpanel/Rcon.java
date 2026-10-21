package pzpanel;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Minimal Source RCON client (Project Zomboid speaks this protocol on RCONPort). */
final class Rcon {
    static String exec(String host, int port, String password, String command, int timeoutMs) throws IOException {
        if (password == null || password.isEmpty()) throw new IOException("RCONPassword в конфиге сервера пустой — RCON выключен");
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            send(out, 1, 3, password);
            // auth: server may send an empty RESPONSE_VALUE first, then AUTH_RESPONSE
            for (int i = 0; i < 2; i++) {
                Packet p = read(in);
                if (p.type == 2) { if (p.id == -1) throw new IOException("Неверный RCONPassword"); break; }
            }
            send(out, 2, 2, command);
            StringBuilder sb = new StringBuilder();
            try {
                Packet p = read(in);
                sb.append(p.body);
                s.setSoTimeout(400); // multi-packet responses
                while (true) { Packet q = read(in); if (q.id != 2) break; sb.append(q.body); }
            } catch (SocketTimeoutException | EOFException ignored) {}
            return sb.toString();
        }
    }

    private record Packet(int id, int type, String body) {}

    private static void send(DataOutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array()); out.flush();
    }

    private static Packet read(DataInputStream in) throws IOException {
        byte[] h = new byte[4]; in.readFully(h);
        int len = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 22) throw new IOException("Некорректный пакет RCON");
        byte[] d = new byte[len]; in.readFully(d);
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int id = bb.getInt(), type = bb.getInt();
        String body = new String(d, 8, Math.max(0, len - 10), StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }
}
