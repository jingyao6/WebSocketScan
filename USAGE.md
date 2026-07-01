# WebSocketScan 使用文档

一个用于 Android 的局域网 WebSocket 扫描 + 连接库，基于 [Java-WebSocket 1.4.0](https://github.com/TooTallNate/Java-WebSocket) 实现。

- **服务端**：一行起 WebSocket 服务，支持广播 / 点对点发消息、连接事件、自动重启
- **客户端**：一行连 WebSocket 服务，支持收发消息、断线回调、自动重连
- **设备发现**：基于 UDP 广播的局域网设备扫描，自动列出同子网内启用了发现功能的对端

---

## 1. 引入

### 1.1 通过 JitPack 引入（推荐）

项目根 `build.gradle`：

```gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

模块 `build.gradle`：

```gradle
dependencies {
    implementation 'com.github.jingyao6:WebSocketScan:v1.0.4'
}
```

### 1.2 源码 / 子模块方式

直接作为子模块或本地 module 依赖：

```gradle
include ':app'
// 或 include ':websocketscan'

dependencies {
    implementation project(':app')
}
```

---

## 2. 权限

在 app 模块的 `AndroidManifest.xml` 中至少声明：

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>
```

如果用明文 `ws://`（非 wss），还需在 `<application>` 标签加：

```xml
<application
    android:usesCleartextTraffic="true"
    ... >
```

---

## 3. 三个 Manager 速览

| 类 | 作用 | 端口 |
|---|---|---|
| `WebSocketServerManager` | 起 WebSocket 服务，监听客户端 | 自定义（demo 用 18899） |
| `WebSocketClientManager` | 连到一个 WebSocket 服务端 | — |
| `UdpDiscoveryManager` | UDP 广播 + 监听，发现同子网内其他设备 | 固定 18888 |

三个 Manager **完全独立**，可以单独使用任意一个。

---

## 4. WebSocketServerManager（服务端）

### 4.1 创建 + 启动

```java
WebSocketServerManager server = new WebSocketServerManager();

server.setOnMessageListener(new WebSocketServerManager.OnMessageListener() {
    @Override public void onConnected(String clientId) { /* 新客户端连上 */ }
    @Override public void onDisconnected(String clientId) { /* 客户端断开 */ }
    @Override public void onMessage(String clientId, String message) { /* 收到消息 */ }
    @Override public void onError(String error) { /* 错误（含服务异常，库内会自动尝试重启） */ }
});

server.start(18899);   // 监听 18899 端口
```

### 4.2 发消息

```java
// 广播给所有客户端
server.broadcast("hello everyone");

// 点对点发给指定客户端
server.sendTo(clientId, "hello you");
```

### 4.3 停止 / 状态

```java
server.stop();              // 停止服务
server.isRunning();         // 是否在运行
server.getClientCount();    // 当前已连接客户端数量
```

### 4.4 行为说明
- 启动失败 / 异常会**自动重试**，最多 5 次，指数退避（2s, 4s, 8s, 16s, 32s）
- 主动 `stop()` 后不再重试
- 客户端 ID 是 `IP:port` 字符串

---

## 5. WebSocketClientManager（客户端）

### 5.1 创建 + 连接

```java
// 方式 1：传 IP + 端口
WebSocketClientManager client = new WebSocketClientManager("192.168.1.100", 18899);

// 方式 2：传完整 URI（支持 wss://）
WebSocketClientManager client = new WebSocketClientManager("ws://192.168.1.100:18899");

client.setOnClientListener(new WebSocketClientManager.OnClientListener() {
    @Override public void onConnected() { /* 连上 */ }
    @Override public void onDisconnected(int code, String reason) { /* 断开 */ }
    @Override public void onMessage(String message) { /* 收到消息 */ }
    @Override public void onError(String error) { /* 错误 */ }
});

client.connect();
```

### 5.2 发消息

```java
client.send("hello server");
```

### 5.3 断开 / 状态

```java
client.disconnect();       // 主动断开，不再自动重连
client.isConnected();      // 是否在连接状态
```

### 5.4 行为说明
- 服务端异常断开（非主动 `disconnect()`）会**自动重连**，最多 5 次，指数退避
- 主动 `disconnect()` 后不会重连

---

## 6. UdpDiscoveryManager（设备发现）

局域网内的设备**互相广播**自己的 `{deviceName, ip, wsPort}`，收到广播时回调 `onDeviceDiscovered`。

### 6.1 启动

```java
UdpDiscoveryManager discovery = new UdpDiscoveryManager();

discovery.setOnDeviceDiscoveredListener((deviceName, ip, wsPort) -> {
    // 收到一个对端的广播
});

discovery.start(localIp, localWsPort, "MyDevice");
//   localIp     —— 本机 IPv4（如 192.168.1.50）
//   localWsPort —— 本机 WebSocket 端口；如果不提供 WebSocket 服务，传 0
//   deviceName  —— 任意可读标识，会被对端收到
```

### 6.2 停止 / 获取已发现列表

```java
discovery.stop();

List<UdpDiscoveryManager.DeviceInfo> known = discovery.getDiscoveredDevices();
for (UdpDiscoveryManager.DeviceInfo d : known) {
    String key = d.getKey();  // "ip:port"
    String name = d.name;
    int wsPort = d.port;      // 0 表示对端没有 WebSocket 服务
}
```

### 6.3 行为说明
- 每 **3 秒**广播一次
- 监听固定端口 **18888**（UDP）
- 会自动收集本机所有网卡（非 loopback 且 up）的子网广播地址
- 同一对端只回调一次

### 6.4 典型用法
1. 调用方启动 `UdpDiscoveryManager.start()` 监听局域网
2. 收到回调后，把对端 IP:port 显示给用户
3. 用户点击某个设备 → 用 `WebSocketClientManager` 连过去

---

## 7. 完整示例：服务端 + 客户端 + 发现联动

```java
// ========== 设备 A：作为服务端 ==========
WebSocketServerManager server = new WebSocketServerManager();
server.setOnMessageListener(new WebSocketServerManager.OnMessageListener() {
    @Override public void onConnected(String id) { }
    @Override public void onDisconnected(String id) { }
    @Override public void onMessage(String id, String msg) {
        server.broadcast("echo: " + msg);
    }
    @Override public void onError(String error) { }
});
server.start(18899);

UdpDiscoveryManager discoA = new UdpDiscoveryManager();
discoA.start(getLocalIpv4(), 18899, "DeviceA");

// ========== 设备 B：作为客户端，先发现，再连 ==========
UdpDiscoveryManager discoB = new UdpDiscoveryManager();
discoB.setOnDeviceDiscoveredListener((name, ip, wsPort) -> {
    if (wsPort > 0) {  // 对端有 WebSocket 服务
        WebSocketClientManager client = new WebSocketClientManager(ip, wsPort);
        client.setOnClientListener(new WebSocketClientManager.OnClientListener() {
            @Override public void onConnected() { }
            @Override public void onDisconnected(int c, String r) { }
            @Override public void onMessage(String msg) { }
            @Override public void onError(String err) { }
        });
        client.connect();
    }
});
discoB.start(getLocalIpv4(), 0, "DeviceB");  // 自己不开服务
```

---

## 8. 注意事项

### 8.1 生命周期
三个 Manager 都持有线程 / 资源，**必须在 `Activity.onDestroy()`（或 Fragment/Service 对应生命周期）调 `stop()`/`disconnect()`**，否则会内存泄漏。

### 8.2 线程模型
- 所有回调（`onMessage`、`onError`、`onConnected`、UDP 设备发现等）都在**库内自建的工作线程**上触发
- 如需更新 UI，**自行 `runOnUiThread` 包裹**

### 8.3 同进程多实例
- 同一个 `WebSocketServerManager` 不能 `start()` 两次，会被忽略
- 不同 `WebSocketClientManager` 实例可以连同一个 / 不同服务端
- `UdpDiscoveryManager` 同一进程内只能跑一个（共享 18888 端口）

### 8.4 IP 获取
Java 反射 / 传统 `NetworkInterface.getNetworkInterfaces()` 在 Android 10+ 受限。本库的 demo 用的是兼容写法，但要拿准确的本机 IP，仍建议：

```java
WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
int ip = wm.getConnectionInfo().getIpAddress();
String ipStr = Formatter.formatIpAddress(ip);
```

### 8.5 Android 13+ 权限
Android 13 (API 33) 起，UDP 多播在某些设备上需要 `NEARBY_WIFI_DEVICES` 权限（视设备 OEM 而定）。如果发现扫描不到对端，加上：

```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
```

并在运行时请求（`ActivityResultContracts.RequestPermission`）。

### 8.6 不支持的功能
- 库内只支持 `ws://`，不支持 `wss://`（如需 TLS 自行扩展）
- 没有内建心跳，依赖 Java-WebSocket 默认实现
- 不支持 SSL 证书校验

---

## 9. Demo 模块

仓库自带 `demo/` 子模块，是一个完整的可运行示例：
- `MainActivity` 选择作为服务端 / 客户端
- `ServerActivity` 启服务、显示客户端连接、广播消息
- `ClientActivity` 启动 UDP 发现、点击设备填入地址、连接、收发消息

直接用 Android Studio 打开项目 → 选择 `demo` 配置运行即可。
