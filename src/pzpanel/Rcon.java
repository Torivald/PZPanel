package pzpanel;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

final class Rcon {
   static String exec(String var0, int var1, String var2, String var3, int var4) throws IOException {
      if (var2 != null && !var2.isEmpty()) {
         String var16;
         try (Socket var5 = new Socket()) {
            var5.connect(new InetSocketAddress(var0, var1), 3000);
            var5.setSoTimeout(var4);
            DataOutputStream var6 = new DataOutputStream(new BufferedOutputStream(var5.getOutputStream()));
            DataInputStream var7 = new DataInputStream(new BufferedInputStream(var5.getInputStream()));
            send(var6, 1, 3, var2);

            for (int var8 = 0; var8 < 2; var8++) {
               Rcon.Packet var9 = read(var7);
               if (var9.type == 2) {
                  if (var9.id == -1) {
                     throw new IOException("Неверный RCONPassword");
                  }
                  break;
               }
            }

            send(var6, 2, 2, var3);
            StringBuilder var14 = new StringBuilder();

            try {
               Rcon.Packet var15 = read(var7);
               var14.append(var15.body);
               var5.setSoTimeout(400);

               while (true) {
                  Rcon.Packet var10 = read(var7);
                  if (var10.id != 2) {
                     break;
                  }

                  var14.append(var10.body);
               }
            } catch (EOFException | SocketTimeoutException var12) {
            }

            var16 = var14.toString();
         }

         return var16;
      } else {
         throw new IOException("RCONPassword в конфиге сервера пустой — RCON выключен");
      }
   }

   private static void send(DataOutputStream var0, int var1, int var2, String var3) throws IOException {
      byte[] var4 = var3.getBytes(StandardCharsets.UTF_8);
      ByteBuffer var5 = ByteBuffer.allocate(14 + var4.length).order(ByteOrder.LITTLE_ENDIAN);
      var5.putInt(10 + var4.length).putInt(var1).putInt(var2).put(var4).put((byte)0).put((byte)0);
      var0.write(var5.array());
      var0.flush();
   }

   private static Rcon.Packet read(DataInputStream var0) throws IOException {
      byte[] var1 = new byte[4];
      var0.readFully(var1);
      int var2 = ByteBuffer.wrap(var1).order(ByteOrder.LITTLE_ENDIAN).getInt();
      if (var2 >= 10 && var2 <= 4194304) {
         byte[] var3 = new byte[var2];
         var0.readFully(var3);
         ByteBuffer var4 = ByteBuffer.wrap(var3).order(ByteOrder.LITTLE_ENDIAN);
         int var5 = var4.getInt();
         int var6 = var4.getInt();
         String var7 = new String(var3, 8, Math.max(0, var2 - 10), StandardCharsets.UTF_8);
         return new Rcon.Packet(var5, var6, var7);
      } else {
         throw new IOException("Некорректный пакет RCON");
      }
   }

   private record Packet(int id, int type, String body) {
   }
}
