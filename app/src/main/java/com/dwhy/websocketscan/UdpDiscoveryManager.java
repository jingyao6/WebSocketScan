package com.dwhy.websocketscan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpDiscoveryManager {

    private static final String TAG = "UdpDiscovery";
    private static final int BROADCAST_PORT = 18888;
    private static final long BROADCAST_INTERVAL = 3000;

    private String deviceIp;
    private int wsPort;
    private String deviceName;

    private DatagramSocket socket;
    private ExecutorService executor;
    private Handler mainHandler;
    private volatile boolean running;
    private final Map<String, DeviceInfo> knownDevices = new ConcurrentHashMap<>();
    private final List<InetAddress> broadcastAddrs = new ArrayList<>();

    private OnDeviceDiscoveredListener listener;

    public static class DeviceInfo {
        public String name, ip;
        public int port;

        public DeviceInfo(String name, String ip, int port) {
            this.name = name;
            this.ip = ip;
            this.port = port;
        }

        public String getKey() {
            return ip + ":" + port;
        }
    }

    public interface OnDeviceDiscoveredListener {
        void onDeviceDiscovered(String deviceName, String ip, int wsPort);
    }

    public UdpDiscoveryManager() {
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 获取已发现设备列表（供页面打开时回显）
     */
    public List<DeviceInfo> getDiscoveredDevices() {
        return new ArrayList<>(knownDevices.values());
    }

    /**
     * @param deviceIp   本机 IP
     * @param wsPort     本机 WebSocket 服务端口
     * @param deviceName 本机设备标识
     */
    public void start(String deviceIp, int wsPort, String deviceName) {
        if (running) return;
        this.deviceIp = deviceIp;
        this.wsPort = wsPort;
        this.deviceName = deviceName;
        running = true;

        // 收集所有网卡的子网广播地址
        collectBroadcastAddrs();

        executor = Executors.newFixedThreadPool(2);
        executor.execute(() -> {
            try {
                ensureSocket();
                Log.d(TAG, "UDP 发现服务启动，本机IP: " + deviceIp + "，端口: " + BROADCAST_PORT);
                executor.execute(new BroadcastTask());
                executor.execute(new ListenTask());
            } catch (IOException e) {
                Log.e(TAG, "启动 UDP socket 失败: " + e.getMessage());
            }
        });
    }

    /**
     * 获取当前可用的 socket；若已关闭则重建
     */
    private synchronized DatagramSocket ensureSocket() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return socket;
        }
        DatagramSocket newSocket = new DatagramSocket(BROADCAST_PORT);
        newSocket.setBroadcast(true);
        newSocket.setReuseAddress(true);
        socket = newSocket;
        return newSocket;
    }

    private void closeSocketQuietly() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
        }
    }

    public void stop() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        knownDevices.clear();
        broadcastAddrs.clear();
        Log.d(TAG, "UDP 发现服务已停止");
    }

    public void setOnDeviceDiscoveredListener(OnDeviceDiscoveredListener listener) {
        this.listener = listener;
    }

    /**
     * 遍历所有网卡，收集各子网的广播地址
     */
    private void collectBroadcastAddrs() {
        broadcastAddrs.clear();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress bcast = addr.getBroadcast();
                    if (bcast != null && addr.getAddress() instanceof Inet4Address) {
                        broadcastAddrs.add(bcast);
                        Log.d(TAG, "找到广播地址: " + bcast.getHostAddress() + " (" + ni.getDisplayName() + ")");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取广播地址异常: " + e.getMessage());
        }
        // 兜底：如果没有找到任何广播地址，用全局广播
        if (broadcastAddrs.isEmpty()) {
            try {
                broadcastAddrs.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
        }
    }

    private class BroadcastTask implements Runnable {
        @Override
        public void run() {
            while (running) {
                DatagramSocket localSocket;
                try {
                    localSocket = ensureSocket();
                } catch (IOException e) {
                    Log.e(TAG, "获取 UDP socket 失败: " + e.getMessage());
                    sleepBeforeRetry();
                    continue;
                }
                try {
                    JSONObject json = new JSONObject();
                    json.put("type", "discovery");
                    json.put("deviceName", deviceName);
                    json.put("ip", deviceIp);
                    json.put("wsPort", wsPort);

                    byte[] data = json.toString().getBytes("UTF-8");
                    // 向每个网卡的子网广播地址发送
                    for (InetAddress bcast : broadcastAddrs) {
                        DatagramPacket packet = new DatagramPacket(data, data.length,
                                bcast, BROADCAST_PORT);
                        localSocket.send(packet);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "广播发送失败: " + e.getMessage());
                    closeSocketQuietly();
                }
                try {
                    Thread.sleep(BROADCAST_INTERVAL);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private class ListenTask implements Runnable {
        @Override
        public void run() {
            byte[] buf = new byte[1024];
            while (running) {
                DatagramSocket localSocket;
                try {
                    localSocket = ensureSocket();
                } catch (IOException e) {
                    Log.e(TAG, "获取 UDP socket 失败: " + e.getMessage());
                    sleepBeforeRetry();
                    continue;
                }
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    localSocket.receive(packet);
                    String jsonStr = new String(packet.getData(), 0, packet.getLength(), "UTF-8");
                    JSONObject json = new JSONObject(jsonStr);

                    String type = json.optString("type");
                    if (!"discovery".equals(type)) continue;

                    String remoteIp = json.getString("ip");
                    if (remoteIp.isEmpty()) continue;
                    // 忽略来自本机的广播
                    if (deviceIp.equals(remoteIp)) continue;

                    int remoteWsPort = json.getInt("wsPort");
                    String remoteName = json.getString("deviceName");
                    String key = remoteIp + ":" + remoteWsPort;

                    if (knownDevices.containsKey(key)) continue;
                    DeviceInfo device = new DeviceInfo(remoteName, remoteIp, remoteWsPort);
                    knownDevices.put(key, device);

                    Log.d(TAG, "发现设备: " + remoteName + " (" + remoteIp + ":" + remoteWsPort + ")");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onDeviceDiscovered(remoteName, remoteIp, remoteWsPort));
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "UDP 监听异常: " + e.getMessage());
                    }
                    closeSocketQuietly();
                } catch (JSONException e) {
                    Log.e(TAG, "解析广播消息失败: " + e.getMessage());
                }
            }
        }
    }
}
