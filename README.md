# WebSocketScan

一个 Android 局域网 WebSocket 扫描 + 连接库。基于 [Java-WebSocket 1.4.0](https://github.com/TooTallNate/Java-WebSocket) 实现。

- **服务端**：一行代码起 WebSocket 服务，支持广播 / 点对点发消息、客户端连接事件、自动重试
- **客户端**：一行代码连 WebSocket 服务，支持收发消息、断线回调、自动重连
- **设备发现**：基于 UDP 广播的局域网设备扫描，自动找到同一子网内的服务端

## JitPack

[![](https://jitpack.io/v/com.dwhy/websocketscan.svg)](https://jitpack.io/#com.dwhy/websocketscan)

```gradle
// 项目根 build.gradle
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}

// 模块 build.gradle
dependencies {
    implementation 'com.github.jingyao6:WebSocketScan:v1.0.5'
}
```

> 完整使用文档见 [USAGE.md](./USAGE.md)，内含三个 Manager 的 API 说明和示例代码。

## License

Apache License 2.0 — see [LICENSE](./LICENSE)。
