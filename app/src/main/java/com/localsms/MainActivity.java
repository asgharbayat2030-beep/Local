package com.localsms;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Enumeration;

public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final int SMS_PERMISSION = 1001;
    private LocalServer server;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 50, 40, 40);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("LocalSMS");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 40, 0, 40);
        status.setText("در حال بررسی مجوز SMS...");

        Button start = new Button(this);
        start.setText("شروع Local Web");

        box.addView(title);
        box.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(start);

        setContentView(box);

        start.setOnClickListener(v -> startServer());

        if (checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_SMS},
                    SMS_PERMISSION);
        } else {
            status.setText("مجوز SMS فعال است. دکمه را بزنید.");
        }
    }

    private void startServer() {
        if (checkSelfPermission(Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "ابتدا مجوز SMS را بدهید.", Toast.LENGTH_LONG).show();
            requestPermissions(
                    new String[]{Manifest.permission.READ_SMS},
                    SMS_PERMISSION);
            return;
        }

        if (server != null) {
            status.setText("سرور از قبل فعال است.");
            return;
        }

        server = new LocalServer();
        server.start();

        String ip = getLocalIpAddress();
        status.setText("سرور فعال است:\nhttp://" + ip + ":" + PORT +
                "\n\nبرای امنیت، فقط دستگاه‌های مورد اعتماد شبکه را متصل کنید.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] p, int[] r) {
        super.onRequestPermissionsResult(requestCode, p, r);
        if (requestCode == SMS_PERMISSION) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                status.setText("مجوز SMS فعال است. حالا «شروع Local Web» را بزنید.");
            } else {
                status.setText("مجوز SMS رد شد؛ بدون آن پیامک‌ها قابل نمایش نیستند.");
            }
        }
    }

    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private class LocalServer extends Thread {
        private ServerSocket socket;
        private final String token = makeToken();

        String getToken() { return token; }

        @Override
        public void run() {
            try {
                socket = new ServerSocket(PORT);
                while (!isInterrupted()) {
                    Socket client = socket.accept();
                    new Client(client).start();
                }
            } catch (Exception ignored) {}
        }

        void stopServer() {
            interrupt();
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private class Client extends Thread {
        private final Socket socket;

        Client(Socket s) { socket = s; }

        @Override
        public void run() {
            try {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String first = in.readLine();
                if (first == null) return;

                while (in.readLine() != null && !in.readLine().isEmpty()) {
                    // headers are ignored
                }

                String path = first.split(" ")[1];
                String token = server.getToken();

                boolean authorized = path.contains("token=" + token);

                String body;
                String type = "text/html; charset=utf-8";

                if (path.equals("/") || path.startsWith("/?")) {
                    body = html(token);
                } else if (path.startsWith("/api/sms") && authorized) {
                    body = readSmsJson();
                    type = "application/json; charset=utf-8";
                } else if (path.startsWith("/api/sms")) {
                    body = "{\"error\":\"unauthorized\"}";
                    type = "application/json; charset=utf-8";
                } else {
                    body = "<h1>404</h1>";
                }

                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                String headers =
                        "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: " + type + "\r\n" +
                        "Content-Length: " + bytes.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
                socket.close();
            } catch (Exception ignored) {}
        }

        private String html(String token) {
            String ip = getLocalIpAddress();
            String api = "http://" + ip + ":" + PORT + "/api/sms?token=" + token;
            return "<!doctype html><html lang='fa' dir='rtl'><meta charset='utf-8'>" +
                    "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<title>LocalSMS</title>" +
                    "<style>body{font-family:sans-serif;max-width:800px;margin:30px auto;padding:15px}" +
                    "button{padding:12px 20px;font-size:16px}pre{white-space:pre-wrap;background:#eee;padding:15px}</style>" +
                    "<h1>LocalSMS</h1><p>سرور محلی فعال است.</p>" +
                    "<button onclick='loadSms()'>نمایش پیامک‌ها</button>" +
                    "<pre id='out'>برای دریافت پیامک‌ها دکمه را بزنید.</pre>" +
                    "<script>async function loadSms(){let r=await fetch('" + api +
                    "');document.getElementById('out').textContent=JSON.stringify(await r.json(),null,2)}</script>" +
                    "</html>";
        }

        private String readSmsJson() {
            StringBuilder json = new StringBuilder("[");
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(
                        android.net.Uri.parse("content://sms"),
                        new String[]{"address", "body", "date", "type"},
                        null, null, "date DESC LIMIT 100");

                boolean first = true;
                if (c != null) {
                    while (c.moveToNext()) {
                        if (!first) json.append(",");
                        first = false;
                        String address = c.getString(0);
                        String body = c.getString(1);
                        long date = c.getLong(2);
                        int type = c.getInt(3);
                        json.append("{")
                                .append("\"address\":\"").append(j(address)).append("\",")
                                .append("\"body\":\"").append(j(body)).append("\",")
                                .append("\"date\":").append(date).append(",")
                                .append("\"type\":").append(type)
                                .append("}");
                    }
                }
            } catch (Exception e) {
                return "{\"error\":\"" + j(e.toString()) + "\"}";
            } finally {
                if (c != null) c.close();
            }
            json.append("]");
            return json.toString();
        }

        private String j(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }
    }

    private String makeToken() {
        return String.format("%06d", new SecureRandom().nextInt(1000000));
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stopServer();
        super.onDestroy();
    }
}
