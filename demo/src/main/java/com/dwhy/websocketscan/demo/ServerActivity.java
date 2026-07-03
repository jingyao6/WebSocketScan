package com.dwhy.websocketscan.demo;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.dwhy.websocketscan.UdpDiscoveryManager;
import com.dwhy.websocketscan.WebSocketServerManager;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class ServerActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PICK_FILE = 1001;

    private TextView tvStatus;
    private EditText etPort;
    private Button btnToggle;
    private ListView lvLog;
    private EditText etMessage;
    private Button btnSend;
    private Button btnSendBinary;

    private WebSocketServerManager serverManager;
    private UdpDiscoveryManager discoveryManager;
    private WifiManager.MulticastLock multicastLock;
    private final List<String> logs = new ArrayList<>();
    private ArrayAdapter<String> logAdapter;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);

        tvStatus = findViewById(R.id.tv_status);
        etPort = findViewById(R.id.et_port);
        btnToggle = findViewById(R.id.btn_toggle);
        lvLog = findViewById(R.id.lv_log);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        btnSendBinary = findViewById(R.id.btn_send_binary);

        logAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, logs);
        lvLog.setAdapter(logAdapter);

        serverManager = new WebSocketServerManager();
        serverManager.setOnMessageListener(new WebSocketServerManager.OnMessageListener() {
            @Override
            public void onConnected(String clientId) {
                appendLog("客户端连接: " + clientId);
                runOnUiThread(() -> updateStatus());
            }

            @Override
            public void onDisconnected(String clientId) {
                appendLog("客户端断开: " + clientId);
                runOnUiThread(() -> updateStatus());
            }

            @Override
            public void onMessage(String clientId, String message) {
                appendLog("← " + clientId + " : " + message);
            }

            @Override
            public void onBinaryMessage(String clientId, ByteBuffer data) {
                appendLog("← " + clientId + " : [二进制] " + data.remaining() + " 字节");
            }

            @Override
            public void onError(String error) {
                appendLog("[错误] " + error);
            }
        });

        discoveryManager = new UdpDiscoveryManager();
        discoveryManager.setOnDeviceDiscoveredListener((name, ip, port) ->
                appendLog("[发现] " + name + " @ " + ip + ":" + port));

        btnToggle.setOnClickListener(v -> {
            if (running) {
                stopServer();
            } else {
                startServer();
            }
        });

        btnSend.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            if (TextUtils.isEmpty(msg)) return;
            if (!serverManager.isRunning()) {
                Toast.makeText(this, R.string.toast_not_running, Toast.LENGTH_SHORT).show();
                return;
            }
            serverManager.broadcast(msg);
            appendLog("→ [广播] " + msg);
            etMessage.setText("");
        });

        btnSendBinary.setOnClickListener(v -> {
            if (!serverManager.isRunning()) {
                Toast.makeText(this, R.string.toast_not_running, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"image/*", "application/*", "text/*"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            try {
                startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startServer() {
        String portText = etPort.getText().toString().trim();
        if (TextUtils.isEmpty(portText)) {
            Toast.makeText(this, R.string.toast_invalid_port, Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Integer.parseInt(portText);
        String ip = getLocalIpv4();
        if (ip == null) {
            Toast.makeText(this, R.string.toast_no_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        serverManager.start(port);
        ensureMulticastLock();
        discoveryManager.start(ip, port, "Server-" + ip);
        running = true;
        btnToggle.setText(R.string.btn_stop);
        etPort.setEnabled(false);
        appendLog("[启动] WebSocket 服务 @ " + ip + ":" + port);
        appendLog("[启动] UDP 发现已开启（端口 18888）");
        updateStatus();
    }

    private void ensureMulticastLock() {
        if (multicastLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicastLock = wifi.createMulticastLock("WebSocketScan");
            multicastLock.setReferenceCounted(false);
        }
        if (!multicastLock.isHeld()) {
            multicastLock.acquire();
        }
    }

    private void releaseMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
    }

    private void stopServer() {
        releaseMulticastLock();
        serverManager.stop();
        discoveryManager.stop();
        running = false;
        btnToggle.setText(R.string.btn_start);
        etPort.setEnabled(true);
        appendLog("[停止] 服务已关闭");
        updateStatus();
    }

    private void updateStatus() {
        String ip = getLocalIpv4();
        String port = etPort.getText().toString().trim();
        int clients = serverManager.getClientCount();
        tvStatus.setText("本机 IP: " + (ip == null ? "?" : ip)
                + "\nWebSocket 端口: " + port
                + "\n运行状态: " + (running ? "运行中" : "已停止")
                + "\n已连接客户端: " + clients);
    }

    private void appendLog(final String line) {
        runOnUiThread(() -> {
            logs.add(timeFmt.format(new Date()) + "  " + line);
            logAdapter.notifyDataSetChanged();
            lvLog.setSelection(logs.size() - 1);
        });
    }

    private static String getLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CODE_PICK_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            appendLog("[取消] 未选择文件");
            return;
        }
        Uri uri = data.getData();
        String name = queryFileName(uri);
        new Thread(() -> {
            try {
                byte[] bytes = readBytesFromUri(uri);
                if (bytes == null || bytes.length == 0) {
                    appendLog("[错误] 文件为空或读取失败");
                    return;
                }
                final int len = bytes.length;
                serverManager.broadcast(ByteBuffer.wrap(bytes));
                appendLog("→ [广播二进制] " + (name != null ? name : "未知文件") + " (" + len + " 字节)");
            } catch (Exception e) {
                appendLog("[错误] 读取文件失败: " + e.getMessage());
            }
        }).start();
    }

    private byte[] readBytesFromUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedInputStream bis = new BufferedInputStream(is);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            Log.w("ServerActivity", "读取文件失败: " + e.getMessage());
            return null;
        }
    }

    private String queryFileName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseMulticastLock();
        if (serverManager != null) serverManager.stop();
        if (discoveryManager != null) discoveryManager.stop();
    }
}
