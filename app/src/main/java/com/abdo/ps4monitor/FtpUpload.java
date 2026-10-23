package com.abdo.ps4monitor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal FTP upload (passive mode, binary) to the FTP server built into GoldHEN (port 2121 according to the GoldHEN documentation I found).
 * Nothing about GoldHEN's login rules is documented in the sources I found: this tries "anonymous" and reports the server's own reply otherwise.
 * Written in Java on purpose: it can be compiled and tested on its own against a local FTP server.
 */
public final class FtpUpload {
    private FtpUpload() {}

    private static final class Reply { final int code; final String text; Reply(int c, String t) { code = c; text = t; } }

    private static Reply read(BufferedReader r) throws IOException {
        String line = r.readLine();
        if (line == null) throw new IOException("The FTP server closed the connection.");
        if (line.length() < 3 || !Character.isDigit(line.charAt(0))) return new Reply(0, line);
        int code;
        try { code = Integer.parseInt(line.substring(0, 3)); } catch (NumberFormatException e) { return new Reply(0, line); }
        if (line.length() > 3 && line.charAt(3) == '-') {
            String end = line.substring(0, 3) + " ";
            StringBuilder sb = new StringBuilder(line);
            for (int i = 0; i < 200; i++) {
                String l = r.readLine();
                if (l == null) break;
                sb.append('\n').append(l);
                if (l.startsWith(end)) break;
            }
            return new Reply(code, sb.toString());
        }
        return new Reply(code, line);
    }

    private static Reply cmd(OutputStream w, BufferedReader r, String c) throws IOException {
        w.write((c + "\r\n").getBytes(StandardCharsets.UTF_8));
        w.flush();
        return read(r);
    }

    private static String bad(String what, Reply rep) { return what + " (FTP reply " + rep.code + ": " + rep.text.replace('\n', ' ') + ")"; }

    /** Uploads [in] ([length] bytes) as [dir]/[name]. Returns null on success, otherwise a short English reason. */
    public static String upload(String host, int port, String dir, String name, InputStream in, long length, IntConsumer progress) {
        try (Socket c = new Socket()) {
            c.connect(new InetSocketAddress(host, port), 5000);
            c.setSoTimeout(20000);
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            OutputStream w = c.getOutputStream();
            Reply rep = read(r);
            if (rep.code != 220) return bad("The FTP server did not greet us", rep);
            rep = cmd(w, r, "USER anonymous");
            if (rep.code == 331 || rep.code == 332) {
                rep = cmd(w, r, "PASS anonymous@");
            }
            if (rep.code != 230) return bad("The FTP server did not accept the login", rep);
            rep = cmd(w, r, "TYPE I");
            if (rep.code != 200) return bad("Binary mode refused", rep);
            rep = cmd(w, r, "CWD " + dir);
            if (rep.code != 250) {
                Reply mk = cmd(w, r, "MKD " + dir);
                rep = cmd(w, r, "CWD " + dir);
                if (rep.code != 250) return bad("Cannot open or create the folder " + dir + " (MKD said " + mk.code + ")", rep);
            }
            rep = cmd(w, r, "PASV");
            if (rep.code != 227) return bad("Passive mode refused", rep);
            Matcher m = Pattern.compile("(\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)").matcher(rep.text);
            if (!m.find()) return bad("Could not read the passive-mode address", rep);
            int dataPort = Integer.parseInt(m.group(5)) * 256 + Integer.parseInt(m.group(6));
            try (Socket d = new Socket()) {
                d.connect(new InetSocketAddress(host, dataPort), 5000);
                d.setSoTimeout(30000);
                w.write(("STOR " + name + "\r\n").getBytes(StandardCharsets.UTF_8));
                w.flush();
                Reply st = read(r);
                if (st.code != 125 && st.code != 150) return bad("The PS4 refused to store " + name, st);
                OutputStream out = d.getOutputStream();
                byte[] buf = new byte[65536];
                long sent = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    sent += n;
                    if (progress != null && length > 0) progress.accept((int) Math.min(100, sent * 100 / length));
                }
                out.flush();
                d.shutdownOutput();
                if (sent != length && length > 0) return "Read " + sent + " bytes from the file, expected " + length + ".";
            }
            rep = read(r);
            if (rep.code != 226 && rep.code != 250) return bad("The transfer did not finish cleanly", rep);
            Reply sz = cmd(w, r, "SIZE " + name);
            if (sz.code == 213) {
                String t = sz.text.length() > 4 ? sz.text.substring(4).trim() : "";
                try { if (length > 0 && Long.parseLong(t) != length) return "The file on the PS4 has " + t + " bytes, expected " + length + ". Do not use it; upload again."; }
                catch (NumberFormatException ignored) { }
            }
            try { cmd(w, r, "QUIT"); } catch (IOException ignored) { }
            return null;
        } catch (java.net.SocketTimeoutException e) {
            return "The PS4 did not answer on FTP port " + port + " in time.";
        } catch (java.net.ConnectException e) {
            return "Cannot connect to " + host + ":" + port + ". Is GoldHEN's FTP server turned on?";
        } catch (IOException e) {
            return "FTP error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }
}
