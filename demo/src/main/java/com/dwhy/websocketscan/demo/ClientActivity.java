package com.dwhy.websocketscan.demo;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.dwhy.websocketscan.UdpDiscoveryManager;
import com.dwhy.websocketscan.WebSocketClientManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class ClientActivity extends AppCompatActivity {

    private TextView tvStatus;
    private EditText etIp;
    private EditText etPort;
    private Button btnToggle;
    private Button btnDiscovery;
    private ListView lvDevices;
    private ListView lvLog;
    private EditText etMessage;
    private Button btnSend;

    private WebSocketClientManager clientManager;
    private UdpDiscoveryManager discoveryManager;
    private WifiManager.MulticastLock multicastLock;
    private final List<String> logs = new ArrayList<>();
    private ArrayAdapter<String> logAdapter;
    private final List<UdpDiscoveryManager.DeviceInfo> devices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private boolean connected = false;
    private boolean discovering = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);

        tvStatus = findViewById(R.id.tv_status);
        etIp = findViewById(R.id.et_ip);
        etPort = findViewById(R.id.et_port);
        btnToggle = findViewById(R.id.btn_toggle);
        btnDiscovery = findViewById(R.id.btn_discovery);
        lvDevices = findViewById(R.id.lv_devices);
        lvLog = findViewById(R.id.lv_log);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);

        logAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, logs);
        lvLog.setAdapter(logAdapter);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        lvDevices.setAdapter(deviceAdapter);

        clientManager = new WebSocketClientManager("", 0);
        clientManager.setOnClientListener(new WebSocketClientManager.OnClientListener() {
            @Override
            public void onConnected() {
                connected = true;
                appendLog("[连接] 已连上服务端");
                runOnUiThread(this::updateStatus);
            }

            @Override
            public void onDisconnected(int code, String reason) {
                connected = false;
                appendLog("[断开] code=" + code + " reason=" + reason);
                runOnUiThread(this::updateStatus);
            }

            @Override
            public void onMessage(String message) {
                appendLog("← " + message);
            }

            @Override
            public void onError(String error) {
                appendLog("[错误] " + error);
            }

            private void updateStatus() {
                ClientActivity.this.updateStatus();
            }
        });

        discoveryManager = new UdpDiscoveryManager();
        discoveryManager.setOnDeviceDiscoveredListener((name, ip, port) -> {
            String key = ip + ":" + port;
            for (UdpDiscoveryManager.DeviceInfo d : devices) {
                if (d.getKey().equals(key)) return;
            }
            devices.add(new UdpDiscoveryManager.DeviceInfo(name, ip, port));
            runOnUiThread(() -> {
                deviceAdapter.add(name + "  " + key);
                deviceAdapter.notifyDataSetChanged();
                appendLog("[发现] " + name + " @ " + key);
            });
        });

        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            UdpDiscoveryManager.DeviceInfo d = devices.get(position);
            etIp.setText(d.ip);
            etPort.setText(String.valueOf(d.port));
        });

        btnToggle.setOnClickListener(v -> {
            if (connected) {
                stopClient();
            } else {
                startClient();
            }
        });

        btnDiscovery.setOnClickListener(v -> {
            if (discovering) {
                stopDiscovery();
            } else {
                startDiscovery();
            }
        });

        btnSend.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            if (TextUtils.isEmpty(msg)) {
                Toast.makeText(this, R.string.toast_empty_message, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!clientManager.isConnected()) {
                Toast.makeText(this, R.string.toast_not_connected, Toast.LENGTH_SHORT).show();
                return;
            }
            clientManager.send(msg);
            appendLog("→ " + msg);
            etMessage.setText("");
        });

        updateStatus();
    }

    private void startClient() {
        String ip = etIp.getText().toString().trim();
        String portText = etPort.getText().toString().trim();
        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(portText)) {
            Toast.makeText(this, R.string.toast_invalid_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        int port = Integer.parseInt(portText);
        // 释放旧 client（如有）
        if (clientManager != null) {
            clientManager.disconnect();
        }
        clientManager = new WebSocketClientManager(ip, port);
        // 重新设置 listener（简化起见，匿名内部类持有外部引用，重新 new 一个）
        clientManager.setOnClientListener(new WebSocketClientManager.OnClientListener() {
            @Override
            public void onConnected() {
                connected = true;
                appendLog("[连接] 已连上服务端");
                runOnUiThread(ClientActivity.this::updateStatus);
            }

            @Override
            public void onDisconnected(int code, String reason) {
                connected = false;
                appendLog("[断开] code=" + code + " reason=" + reason);
                runOnUiThread(ClientActivity.this::updateStatus);
            }

            @Override
            public void onMessage(String message) {
                appendLog("← " + message);
            }

            @Override
            public void onError(String error) {
                appendLog("[错误] " + error);
            }
        });
        clientManager.connect();
        btnToggle.setText(R.string.btn_disconnect);
        etIp.setEnabled(false);
        etPort.setEnabled(false);
        appendLog("[连接中] " + ip + ":" + port);
        updateStatus();
    }

    private void stopClient() {
        clientManager.disconnect();
        connected = false;
        btnToggle.setText(R.string.btn_connect);
        etIp.setEnabled(true);
        etPort.setEnabled(true);
        appendLog("[停止] 客户端已断开");
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

    private void startDiscovery() {
        String ip = getLocalIpv4();
        if (ip == null) {
            Toast.makeText(this, R.string.toast_no_ip, Toast.LENGTH_SHORT).show();
            return;
        }
        // 客户端不提供 WebSocket 服务，wsPort 传 0
        ensureMulticastLock();
        discoveryManager.start(ip, 0, "Client-" + ip);
        discovering = true;
        btnDiscovery.setText(R.string.btn_discovery_stop);
        appendLog("[发现] UDP 监听已开启，等待局域网服务端/客户端广播...");
    }

    private void stopDiscovery() {
        releaseMulticastLock();
        discoveryManager.stop();
        devices.clear();
        deviceAdapter.clear();
        deviceAdapter.notifyDataSetChanged();
        discovering = false;
        btnDiscovery.setText(R.string.btn_discovery_start);
        appendLog("[发现] UDP 监听已停止");
    }

    private void updateStatus() {
        tvStatus.setText("运行状态: " + (connected ? "已连接" : "未连接")
                + "\n发现状态: " + (discovering ? "扫描中" : "未扫描"));
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
    protected void onDestroy() {
        super.onDestroy();
        releaseMulticastLock();
        if (clientManager != null) clientManager.disconnect();
        if (discoveryManager != null) discoveryManager.stop();
    }
}
