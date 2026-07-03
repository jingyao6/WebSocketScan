package com.dwhy.websocketscan;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public class WebSocketClientManager {

    private static final String TAG = "WSClientManager";
    private static final int MAX_RECONNECT_COUNT = 5;
    private static final long RECONNECT_BASE_DELAY_MS = 2000L;

    private final String serverUri;
    private WebSocketClient client;
    private OnClientListener listener;
    private final AtomicInteger reconnectCount = new AtomicInteger(0);
    private volatile boolean manuallyStopped = false;
    private final Handler reconnectHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;

    public interface OnClientListener {
        void onConnected();
        void onDisconnected(int code, String reason);
        void onMessage(String message);
        void onBinaryMessage(ByteBuffer data);
        void onError(String error);
    }

    public WebSocketClientManager(String serverUri) {
        this.serverUri = serverUri;
    }

    public WebSocketClientManager(String serverIp, int serverPort) {
        this("ws://" + serverIp + ":" + serverPort);
    }

    public void setOnClientListener(OnClientListener listener) {
        this.listener = listener;
    }

    public void connect() {
        if (client != null && client.isOpen()) {
            Log.w(TAG, "客户端已连接");
            return;
        }
        manuallyStopped = false;
        reconnectCount.set(0);
        doConnect();
    }

    private void doConnect() {
        try {
            URI uri = new URI(serverUri);
            client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.d(TAG, "已连接到服务端: " + serverUri);
                    reconnectCount.set(0);
                    if (listener != null) {
                        listener.onConnected();
                    }
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "收到消息: " + message);
                    if (listener != null) {
                        listener.onMessage(message);
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    Log.d(TAG, "收到二进制消息, 长度: " + bytes.remaining());
                    if (listener != null) {
                        listener.onBinaryMessage(bytes);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d(TAG, "连接断开 code=" + code + " reason=" + reason + " remote=" + remote);
                    if (listener != null) {
                        listener.onDisconnected(code, reason);
                    }
                    if (!manuallyStopped) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    String err = ex != null ? ex.getMessage() : "unknown";
                    Log.e(TAG, "WebSocket 客户端错误: " + err);
                    if (listener != null) {
                        listener.onError(err);
                    }
                }
            };
            client.setConnectionLostTimeout(0);
            client.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "URI 解析失败: " + e.getMessage());
            if (listener != null) {
                listener.onError("URI 解析失败: " + e.getMessage());
            }
        }
    }

    private void scheduleReconnect() {
        if (manuallyStopped) return;
        if (reconnectCount.get() >= MAX_RECONNECT_COUNT) {
            Log.e(TAG, "已达最大重连次数(" + MAX_RECONNECT_COUNT + ")，停止自动重连");
            return;
        }
        int attempt = reconnectCount.incrementAndGet();
        long delay = RECONNECT_BASE_DELAY_MS * (1L << Math.min(attempt - 1, 5));
        Log.w(TAG, "将在 " + delay + "ms 后第 " + attempt + " 次尝试重连");
        cancelPendingReconnect();
        reconnectRunnable = () -> {
            try {
                doConnect();
            } catch (Exception e) {
                Log.e(TAG, "重连异常: " + e.getMessage());
                scheduleReconnect();
            }
        };
        reconnectHandler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelPendingReconnect() {
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    public void disconnect() {
        manuallyStopped = true;
        cancelPendingReconnect();
        if (client != null) {
            try {
                client.closeBlocking();
            } catch (InterruptedException e) {
                Log.e(TAG, "关闭连接异常: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "关闭连接异常: " + e.getMessage());
            }
            client = null;
            Log.d(TAG, "WebSocket 客户端已断开");
        }
    }

    public void send(String message) {
        if (client != null && client.isOpen()) {
            client.send(message);
            Log.i(TAG, "发送消息: " + message);
        } else {
            Log.w(TAG, "客户端未连接，发送失败: " + message);
        }
    }

    /**
     * 发送二进制消息
     */
    public void send(ByteBuffer data) {
        if (client != null && client.isOpen()) {
            client.send(data);
            Log.i(TAG, "发送二进制消息, 长度: " + data.remaining());
        } else {
            Log.w(TAG, "客户端未连接，发送二进制失败");
        }
    }

    public boolean isConnected() {
        return client != null && client.isOpen();
    }
}
