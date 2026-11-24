# Informe Técnico de Integración: **CamLink USB (Android)** ↔ **LucyCast Studio (PC)**

Este documento resume los ajustes implementados en **CamLink USB** para cumplir al 100% con el contrato `schemaVersion: 1.2.0` de **LucyCast Studio** (`http://localhost:3000` + `public/scripts/connect.bat`) y describe todo lo que el proyecto de PC debe verificar/mantener para vinculación automática Plug & Play por USB.

---

## 1. Ajustes Aplicados en `CamLink USB` (Android APK)

1. **`KEEP_CONTROL_SERVER_ALIVE` (`UdpDiscoveryBeacon.kt` + `StreamEngine.kt`):**
   - El servidor HTTP de control (`videoPort + 1`, por defecto `7879`) y el listener UDP `7879` permanecen **siempre activos** (incluso durante cambios de lente, resolución, FPS o protocolo).
   - Durante los `300–600 ms` de reinicio de `Camera2`/`MediaCodec`/`SocketStreamServer`, `/status` continúa respondiendo con `"restarting": true` para que LucyCast no pierda la conexión ni desconecte la sesión.
2. **`FIX_CORS_AND_OPTIONS` (`UdpDiscoveryBeacon.kt` + `SocketStreamServer.kt`):**
   - Todas las respuestas HTTP en puertos `7879` y `7878` (`/video`, `/monitor`) incluyen:
     - `Access-Control-Allow-Origin: *`
     - `Access-Control-Allow-Methods: GET, POST, OPTIONS`
     - `Access-Control-Allow-Headers: *`
     - `Access-Control-Allow-Private-Network: true`
     - `Cache-Control: no-store`
   - Todas las solicitudes preflight `OPTIONS` responden **`HTTP/1.1 204 No Content`**.
3. **`IMPLEMENT_REMOTE_CONTROL_ENDPOINTS` (`UdpDiscoveryBeacon.kt` + `StreamEngine.kt` + `CamLinkApp.kt`):**
   - `GET /status` → devuelve JSON completo (`live`, `restarting`, `port`, `proto`, `lens`, `resolution`, `width`, `height`, `fps`, `sensorOrientation`, `torch`, `flashSupported`, `connectedClients`, `usbIps`, `state`, `ctrlPort`, `udpPort`, `actualFps`, `bitrateMbps`, `hevcSupported`, `device`, `appId`).
   - `GET /use-mjpeg`, `GET /use-h264`, `GET /use-hevc`
   - `GET /protocol?value=H264_TCP|HEVC_TCP|MJPEG_HTTP`
   - `GET /flip` (opcional `?lens=BACK|FRONT`)
   - `GET /torch?state=toggle|on|off`
   - `GET /resolution?value=FHD_1080P|HD_720P|SD_480P|LOW_360P`
   - `GET /fps?value=60|30|24`
   - `GET /keyframe`
   - `GET /start` y `GET /stop`
4. **`KEEP_NATIVE_SENSOR_ORIENTATION` (`StreamEngine.kt`):**
   - Los frames se codifican en orientación nativa del sensor sin rotación en CPU, reportando `sensorOrientation` (`0|90|180|270`), `width` y `height` en `/status` y en los paquetes UDP.
5. **`ENSURE_LOW_LATENCY_ENCODER_HEADERS` (`SocketStreamServer.kt`):**
   - `H264_TCP` / `HEVC_TCP`: envía `SPS/PPS` (y `VPS` en HEVC) cacheados + solicita IDR inmediato + filtra paquetes `P`/`B` hasta que llega el primer frame IDR limpio, sobre socket con `TCP_NODELAY = true`.
   - `MJPEG_HTTP`: stream `multipart/x-mixed-replace; boundary=camlinkframe` con `Content-Length` exacto por cada frame JPEG.
6. **`AUTO_START_ON_USB_READY_OPTION` (`UsbDiagnosticsMonitor.kt` + `StreamViewModel.kt`):**
   - Detecta `USB_STATE` (`rndis`, `usb`, `ncm`, `tether`, `adb`), re-escanea interfaces con retardo de `500 ms` y `1500 ms` para capturar la IP RNDIS exacta al instante, muestra `"Listo para LucyCast · USB Tethering"` / `"Listo para LucyCast · ADB Listo"`, y soporta auto-transmisión si `CAMERA` está concedido.
7. **Discovery UDP compatible (`UdpDiscoveryBeacon.kt`):**
   - Responde `CAMLINK_WHO` en puerto `7879` y emite `CAMLINK_LIVE|ip=<usbIp>|port=7878|proto=...|lens=<BACK|FRONT>|w=<width>|h=<height>|fps=<fps>|...` hacia el broadcast de la subred RNDIS/USB.

---

## 2. Qué Debe Tener y Ajustar el Proyecto de PC (**LucyCast Studio**)

1. **Auto-detección multi-ruta (`/api/usb-cam/discover` + `connect.bat`):**
   - **Ruta A (ADB):** Detectar dispositivos `adb devices` en estado `device` y ejecutar con `-s <serial>`:
     - `adb -s <serial> forward tcp:7878 tcp:7878`
     - `adb -s <serial> forward tcp:7879 tcp:7879`
     - Probar `GET http://127.0.0.1:7879/status`.
   - **Ruta B (USB Tethering RNDIS/NCM sin ADB):** Obtener dinámicamente el **IPv4 Default Gateway** de las interfaces de red activas del SO (ej. `192.168.42.129` u otra subred dinámica) y probar `GET http://<gatewayIp>:7879/status` con timeout `350–500 ms`.
   - **Ruta C (UDP Discovery `7879`):** Enviar `CAMLINK_WHO` tanto a `255.255.255.255:7879` como al broadcast de cada subred IPv4 local, y aceptar `CAMLINK_LIVE|...` o `CAMLINK_READY|...`.
2. **Selección automática de pipeline de video:**
   - Si existe `ffmpeg` en el PC y `proto` es `H264_TCP` o `HEVC_TCP`:
     `ffmpeg -fflags nobuffer -flags low_delay -probesize 32 -analyzeduration 0 -f <h264|hevc> -i tcp://<host>:7878 -an -f rawvideo -pix_fmt bgr24 pipe:1`
   - Si no hay FFmpeg: llamar `GET http://<host>:7879/use-mjpeg`, esperar `350 ms` y consumir `http://<host>:7878/video` en `<img>` / `createImageBitmap` / Canvas.
3. **Manejo de `restarting: true` y rotación GPU:**
   - Mientras `/status` devuelva `"restarting": true` (por `/flip`, `/resolution`, `/fps` o `/protocol`), esperar `~250 ms` y re-conectar el stream de video sin marcar el teléfono como desconectado.
   - Rotar (`0/90/180/270`) y recortar 16:9 en GPU/Canvas en LucyCast según `sensorOrientation`, `width` y `height`.
