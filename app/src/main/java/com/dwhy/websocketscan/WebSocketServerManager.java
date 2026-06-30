package com.dwhy.websocketscan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class WebSocketServerManager {

    private static final String TAG = "WSServerManager";
    private static final int MAX_RESTART_COUNT = 5;
    private static final long RESTART_BASE_DELAY_MS = 2000L;

    private WebSocketServer server;
    private int port;
    private OnMessageListener listener;
    private final ConcurrentHashMap<String, WebSocket> clients = new ConcurrentHashMap<>();
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private volatile boolean manuallyStopped = false;
    private final Handler restartHandler = new Handler(Looper.getMainLooper());
    private Runnable restartRunnable;

    public interface OnMessageListener {
        void onConnected(String clientId);
        void onDisconnected(String clientId);
        void onMessage(String clientId, String message);
        void onError(String error);
    }

    public void setOnMessageListener(OnMessageListener listener) {
        this.listener = listener;
    }

    public void start(int port) {
        if (server != null) {
            Log.w(TAG, "服务已在运行中");
            return;
        }
        manuallyStopped = false;
        doStart(port);
    }

    private void doStart(int port) {
        this.port = port;
        server = new WebSocketServer(new InetSocketAddress(port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                String clientId = conn.getRemoteSocketAddress().toString();
                clients.put(clientId, conn);
                Log.d(TAG, "客户端连接: " + clientId);
                if (listener != null) {
                    listener.onConnected(clientId);
                }
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                String clientId = conn.getRemoteSocketAddress().toString();
                clients.remove(clientId);
                Log.d(TAG, "客户端断开: " + clientId);
                if (listener != null) {
                    listener.onDisconnected(clientId);
                }
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                String clientId = conn.getRemoteSocketAddress().toString();
                Log.d(TAG, "收到消息 from " + clientId + ": " + message);
                if (listener != null) {
                    listener.onMessage(clientId, message);
                }
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                String err = ex != null ? ex.getMessage() : "unknown";
                Log.e(TAG, "WebSocket 错误: " + err);
                if (listener != null) {
                    listener.onError(err);
                }
                // 忽略 java-websocket 库内部 NPE（conn 句柄异常），不重启服务
                if (err != null && err.contains("InetSocketAddress") && err.contains("toString")) {
                    Log.w(TAG, "忽略 java-websocket 内部 NPE，不重启服务");
                    return;
                }
                scheduleRestart();
            }

            @Override
            public void onStart() {
                Log.d(TAG, "WebSocket 服务已启动，端口: " + port);
                restartCount.set(0);
            }
        };
        try {
            server.setReuseAddr(true);
            server.setConnectionLostTimeout(0);
            server.start();
        } catch (Exception e) {
            Log.e(TAG, "启动 WebSocket 失败: " + e.getMessage());
            server = null;
            scheduleRestart();
        }
    }

    private void scheduleRestart() {
        if (manuallyStopped || server == null && restartCount.get() >= MAX_RESTART_COUNT) {
            Log.e(TAG, "已达最大重启次数(" + MAX_RESTART_COUNT + ")，停止自动重启");
            return;
        }
        if (restartCount.get() >= MAX_RESTART_COUNT) {
            Log.e(TAG, "已达最大重启次数(" + MAX_RESTART_COUNT + ")，停止自动重启");
            return;
        }
        int attempt = restartCount.incrementAndGet();
        long delay = RESTART_BASE_DELAY_MS * (1L << Math.min(attempt - 1, 5));
        Log.w(TAG, "将在 " + delay + "ms 后第 " + attempt + " 次尝试重启");
        cancelPendingRestart();
        restartRunnable = () -> {
            try {
                if (server != null) {
                    try { server.stop(); } catch (Exception ignored) {}
                    server = null;
                }
                clients.clear();
                doStart(port);
            } catch (Exception e) {
                Log.e(TAG, "重启异常: " + e.getMessage());
                scheduleRestart();
            }
        };
        restartHandler.postDelayed(restartRunnable, delay);
    }

    private void cancelPendingRestart() {
        if (restartRunnable != null) {
            restartHandler.removeCallbacks(restartRunnable);
            restartRunnable = null;
        }
    }

    public void stop() {
        manuallyStopped = true;
        cancelPendingRestart();
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止服务异常: " + e.getMessage());
            }
            server = null;
            clients.clear();
            Log.d(TAG, "WebSocket 服务已停止");
        }
    }

    /**
     * 向指定客户端发送消息
     */
    public void sendTo(String clientId, String message) {
        WebSocket conn = clients.get(clientId);
        if (conn != null && conn.isOpen()) {
            conn.send(message);
            Log.i(TAG, "发送消息 to " + clientId + ": " + message);
        } else {
            Log.w(TAG, "客户端不在线: " + clientId);
        }
    }

    /**
     * 向所有已连接客户端广播消息
     */
    public void broadcast(String message) {
        for (WebSocket conn : clients.values()) {
            if (conn.isOpen()) {
                conn.send(message);
                Log.i(TAG, "发送广播 ："+ message);
            }
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getClientCount() {
        return clients.size();
    }
}
