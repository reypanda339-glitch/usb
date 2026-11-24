<# :
@echo off
setlocal
title CamLink USB + PANDAFACE / Auto-Lanzador PC
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-Expression ([System.IO.File]::ReadAllText('%~f0'))"
exit /b
#>

$tcpPort = __TCP_PORT__
$ctrlPort = __CTRL_PORT__
$udpPort = __UDP_PORT__
$defaultCodec = "__DEFAULT_CODEC__"
$studioUrl = "http://localhost:3000"

$Host.UI.RawUI.WindowTitle = "CamLink USB - Esperando movil..."
Write-Host "==============================================================" -ForegroundColor Cyan
Write-Host "  CamLink USB - Puente Automatico PC / PANDAFACE" -ForegroundColor Cyan
Write-Host "  Puerto Video: $tcpPort | Control: $ctrlPort | UDP: $udpPort" -ForegroundColor Gray
Write-Host "==============================================================" -ForegroundColor Cyan

$adbExe = (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
if (-not $adbExe -and $env:LOCALAPPDATA) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) { $adbExe = $sdkAdb }
}
$ffplayExe = (Get-Command ffplay -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
$vlcExe = (Get-Command vlc -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
$edgeExe = (Get-Command msedge -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
if (-not $edgeExe) {
    $edgePath = "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    if (Test-Path $edgePath) { $edgeExe = $edgePath }
}
$chromeExe = (Get-Command chrome -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
if (-not $chromeExe) {
    $chromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
    if (Test-Path $chromePath) { $chromeExe = $chromePath }
}

if ($ffplayExe) { Write-Host "[OK] ffplay detectado (modo H.264/HEVC baja latencia)." -ForegroundColor Green }
elseif ($vlcExe) { Write-Host "[OK] VLC detectado." -ForegroundColor Green }
elseif ($edgeExe -or $chromeExe) { Write-Host "[OK] Navegador detectado (auto-cambiara a MJPEG si no hay ffplay)." -ForegroundColor Green }

if ($adbExe) { Write-Host "[OK] ADB detectado: $adbExe" -ForegroundColor Green }
else { Write-Host "[INFO] Sin ADB: usando deteccion USB Tethering (RNDIS/NCM) + UDP." -ForegroundColor Yellow }

Write-Host ""
Write-Host "1) Conecta el cable USB al movil." -ForegroundColor White
Write-Host "2) Activa 'Anclaje USB' (Tethering) o 'Depuracion USB' (ADB)." -ForegroundColor White
Write-Host "3) Abre CamLink USB y pulsa 'Transmitir' (o activa Auto-transmitir)." -ForegroundColor White
Write-Host ""

function Test-PrivateIpv4([string]$ip) {
    if (-not $ip) { return $false }
    if ($ip -eq "127.0.0.1") { return $true }
    if ($ip -notmatch '^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$') { return $false }
    $parts = $ip.Split('.') | ForEach-Object { [int]$_ }
    if ($parts[0] -eq 10) { return $true }
    if ($parts[0] -eq 192 -and $parts[1] -eq 168) { return $true }
    if ($parts[0] -eq 172 -and $parts[1] -ge 16 -and $parts[1] -le 31) { return $true }
    return $false
}

function Get-AdbDeviceSerials([string]$adbPath) {
    $serials = New-Object System.Collections.Generic.List[string]
    if (-not $adbPath) { return $serials }
    try {
        $lines = & $adbPath devices 2>$null
        foreach ($line in $lines) {
            if ($line -match '^([^\s]+)\s+device\s*$') {
                $serials.Add($Matches[1])
            }
        }
    } catch {}
    return $serials
}

function Get-CandidatePhoneIps {
    $ips = New-Object System.Collections.Generic.List[string]
    $addIp = {
        param([string]$candidate)
        if ($candidate -and $candidate -ne "127.0.0.1" -and -not $candidate.EndsWith(".255") -and (Test-PrivateIpv4 $candidate)) {
            if (-not $ips.Contains($candidate)) {
                $ips.Add($candidate)
            }
        }
    }

    try {
        $configs = Get-NetIPConfiguration -ErrorAction SilentlyContinue
        foreach ($cfg in $configs) {
            if ($cfg.IPv4DefaultGateway) {
                foreach ($gw in $cfg.IPv4DefaultGateway) {
                    & $addIp $gw.NextHop
                }
            }
        }
    } catch {}

    try {
        $wmi = Get-CimInstance Win32_NetworkAdapterConfiguration -Filter "IPEnabled=True" -ErrorAction SilentlyContinue
        foreach ($nic in $wmi) {
            if ($nic.DHCPServer) { & $addIp $nic.DHCPServer }
            if ($nic.DefaultIPGateway) {
                foreach ($dgw in $nic.DefaultIPGateway) { & $addIp $dgw }
            }
        }
    } catch {}

    try {
        $neighbors = Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.State -in @("Reachable", "Stale", "Delay", "Probe", "Permanent") }
        foreach ($n in $neighbors) { & $addIp $n.IPAddress }
    } catch {}

    return $ips
}

function Get-LocalBroadcastTargets {
    $targets = New-Object System.Collections.Generic.List[string]
    $targets.Add("255.255.255.255")
    try {
        $addrs = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -ne "127.0.0.1" -and (Test-PrivateIpv4 $_.IPAddress) }
        foreach ($a in $addrs) {
            if ($a.PrefixLength -ge 8 -and $a.PrefixLength -le 30) {
                $bytes = ([System.Net.IPAddress]::Parse($a.IPAddress)).GetAddressBytes()
                [Array]::Reverse($bytes)
                $ipInt = [BitConverter]::ToUInt32($bytes, 0)
                $maskInt = ([uint32]::MaxValue) -shl (32 - $a.PrefixLength)
                $bcastInt = ($ipInt -band $maskInt) -bor (-bnot $maskInt)
                $bcastBytes = [BitConverter]::GetBytes([uint32]$bcastInt)
                [Array]::Reverse($bcastBytes)
                $bcastIp = ([System.Net.IPAddress]::new($bcastBytes)).ToString()
                if (-not $targets.Contains($bcastIp)) {
                    $targets.Add($bcastIp)
                }
            }
        }
    } catch {}
    return $targets
}

function Probe-ControlStatus([string]$ip, [int]$cPort) {
    try {
        $req = [System.Net.HttpWebRequest]::Create("http://${ip}:${cPort}/status")
        $req.Timeout = 400
        $req.ReadWriteTimeout = 400
        $resp = $req.GetResponse()
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $json = $sr.ReadToEnd()
        $sr.Close()
        $resp.Close()
        return ($json | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Test-PandafaceLocalServer {
    try {
        $req = [System.Net.HttpWebRequest]::Create("$studioUrl/api/health")
        $req.Timeout = 600
        $resp = $req.GetResponse()
        $resp.Close()
        return $true
    } catch {
        return $false
    }
}

function Notify-Pandaface([hashtable]$payload) {
    try {
        $json = $payload | ConvertTo-Json -Depth 4 -Compress
        Invoke-RestMethod -Uri "$studioUrl/api/usb-cam/register" -Method Post -ContentType "application/json" -Body $json -TimeoutSec 2 | Out-Null
        return $true
    } catch {
        return $false
    }
}

function Request-MjpegMode([string]$ip, [int]$cPort) {
    try {
        $req = [System.Net.HttpWebRequest]::Create("http://${ip}:${cPort}/use-mjpeg")
        $req.Timeout = 800
        $resp = $req.GetResponse()
        $resp.Close()
        Start-Sleep -Milliseconds 350
        return $true
    } catch {
        return $false
    }
}

function Open-CamWindow([string]$hostIp, [int]$port, [string]$proto, [int]$cPort) {
    $codec = if ($proto -eq "HEVC_TCP") { "hevc" } elseif ($proto -eq "MJPEG_HTTP") { "mjpeg" } else { "h264" }

    if ($codec -ne "mjpeg" -and -not $ffplayExe -and -not $vlcExe) {
        Write-Host "[INFO] Sin ffplay/VLC para H.264 -> activando modo MJPEG compatible en el movil..." -ForegroundColor Yellow
        if (Request-MjpegMode $hostIp $cPort) {
            $codec = "mjpeg"
        }
    }

    if ($codec -eq "mjpeg") {
        $streamUrl = "http://${hostIp}:${port}/video"
        $monitorUrl = "http://${hostIp}:${port}/monitor"
        if ($ffplayExe) {
            $p = Start-Process -PassThru $ffplayExe -ArgumentList @(
                "-window_title", "CamLink USB Live",
                "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop",
                "-f", "mpjpeg", $streamUrl
            )
            return @{ proc = $p; isBrowser = $false }
        }
        if ($edgeExe) {
            $p = Start-Process -PassThru $edgeExe -ArgumentList @("--new-window", "--app=$monitorUrl")
            return @{ proc = $p; isBrowser = $true }
        }
        if ($chromeExe) {
            $p = Start-Process -PassThru $chromeExe -ArgumentList @("--new-window", "--app=$monitorUrl")
            return @{ proc = $p; isBrowser = $true }
        }
        $p = Start-Process -PassThru $monitorUrl
        return @{ proc = $p; isBrowser = $true }
    } else {
        $tcpUrl = "tcp://${hostIp}:${port}"
        if ($ffplayExe) {
            $p = Start-Process -PassThru $ffplayExe -ArgumentList @(
                "-window_title", "CamLink USB Live",
                "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop",
                "-probesize", "32", "-analyzeduration", "0",
                "-f", $codec, $tcpUrl
            )
            return @{ proc = $p; isBrowser = $false }
        }
        if ($vlcExe) {
            $p = Start-Process -PassThru $vlcExe -ArgumentList @(
                "--demux=$codec", "--network-caching=0", $tcpUrl
            )
            return @{ proc = $p; isBrowser = $false }
        }
        return $null
    }
}

$udp = New-Object System.Net.Sockets.UdpClient(0)
$udp.EnableBroadcast = $true
$udp.Client.ReceiveTimeout = 280
$remoteEp = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0)

$viewerProc = $null
$viewerIsBrowser = $false
$activeKey = ""
$lastStatusTime = [DateTime]::MinValue
$lastNotifyTime = [DateTime]::MinValue
$missCount = 0

while ($true) {
    if ($viewerProc -and -not $viewerIsBrowser -and $viewerProc.HasExited) {
        $viewerProc = $null
        $activeKey = ""
    }

    $detected = $null
    $gateways = @()

    # 1. ADB por serial especifico (-s)
    $adbSerials = Get-AdbDeviceSerials $adbExe
    foreach ($serial in $adbSerials) {
        try {
            & $adbExe -s $serial forward "tcp:$tcpPort" "tcp:$tcpPort" 2>$null | Out-Null
            & $adbExe -s $serial forward "tcp:$ctrlPort" "tcp:$ctrlPort" 2>$null | Out-Null
            $st = Probe-ControlStatus "127.0.0.1" $ctrlPort
            if ($st) {
                $detected = @{
                    ip = "127.0.0.1"
                    port = if ($st.port) { [int]$st.port } else { $tcpPort }
                    ctrlPort = $ctrlPort
                    live = [bool]$st.live
                    proto = if ($st.proto) { [string]$st.proto } else { "H264_TCP" }
                    via = "adb"
                    device = if ($st.device) { [string]$st.device } else { "ADB $serial" }
                    status = $st
                }
                if ($detected.live) { break }
            }
        } catch {}
    }

    # 2. Interfaces USB RNDIS/NCM (Gateway + DHCPServer + Vecinos ARP)
    if (-not $detected -or -not $detected.live) {
        $gateways = Get-CandidatePhoneIps
        foreach ($gwIp in $gateways) {
            $st = Probe-ControlStatus $gwIp $ctrlPort
            if ($st) {
                $candidate = @{
                    ip = $gwIp
                    port = if ($st.port) { [int]$st.port } else { $tcpPort }
                    ctrlPort = $ctrlPort
                    live = [bool]$st.live
                    proto = if ($st.proto) { [string]$st.proto } else { "H264_TCP" }
                    via = "usb-tethering"
                    device = if ($st.device) { [string]$st.device } else { $gwIp }
                    status = $st
                }
                if ($candidate.live -or -not $detected) {
                    $detected = $candidate
                }
                if ($detected.live) { break }
            }
        }
    }

    # 3. Rafaga UDP CAMLINK_WHO (acepta CAMLINK_LIVE y CAMLINK_READY)
    if (-not $detected -or -not $detected.live) {
        try {
            $whoBytes = [System.Text.Encoding]::UTF8.GetBytes("CAMLINK_WHO")
            foreach ($bcastIp in (Get-LocalBroadcastTargets)) {
                $bcastEp = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Parse($bcastIp), $udpPort)
                $null = $udp.Send($whoBytes, $whoBytes.Length, $bcastEp)
            }
            $replyBytes = $udp.Receive([ref]$remoteEp)
            $msg = [System.Text.Encoding]::UTF8.GetString($replyBytes)
            if ($msg.StartsWith("CAMLINK_LIVE") -or $msg.StartsWith("CAMLINK_READY")) {
                $kv = @{}
                foreach ($part in $msg.Split("|")) {
                    if ($part -match "=") {
                        $pair = $part.Split("=", 2)
                        $kv[$pair[0]] = $pair[1]
                    }
                }
                $replyIp = if ($kv["ip"] -and $kv["ip"] -ne "127.0.0.1" -and (Test-PrivateIpv4 $kv["ip"])) {
                    $kv["ip"]
                } else {
                    $remoteEp.Address.IPAddressToString
                }
                if (Test-PrivateIpv4 $replyIp) {
                    $rPort = if ($kv["port"]) { [int]$kv["port"] } else { $tcpPort }
                    $isLive = $msg.StartsWith("CAMLINK_LIVE")
                    $st = Probe-ControlStatus $replyIp ($rPort + 1)
                    $detected = @{
                        ip = $replyIp
                        port = if ($st -and $st.port) { [int]$st.port } else { $rPort }
                        ctrlPort = $rPort + 1
                        live = if ($st) { [bool]$st.live } else { $isLive }
                        proto = if ($st -and $st.proto) { [string]$st.proto } elseif ($kv["proto"]) { $kv["proto"] } else { "H264_TCP" }
                        via = "udp"
                        device = if ($kv["device"]) { $kv["device"] } else { $replyIp }
                        status = $st
                    }
                }
            }
        } catch {}
    }

    if ($detected) {
        $now = Get-Date
        $syncedToStudio = $false
        if (($now - $lastNotifyTime).TotalSeconds -ge 3 -or $activeKey -ne "$($detected.ip):$($detected.port)") {
            if (Test-PandafaceLocalServer) {
                $syncedToStudio = Notify-Pandaface $detected
                $lastNotifyTime = $now
            }
        }

        if ($detected.live) {
            $missCount = 0
            $key = "$($detected.ip):$($detected.port)"
            if ($activeKey -ne $key) {
                if ($syncedToStudio) {
                    Write-Host "[VINCULADO CON PANDAFACE] $key ($($detected.via) / $($detected.proto))" -ForegroundColor Green
                    $activeKey = $key
                } else {
                    Write-Host "[EN VIVO] Movil conectado en $key ($($detected.proto)). Abriendo pantalla..." -ForegroundColor Green
                    $res = Open-CamWindow $detected.ip $detected.port $detected.proto $detected.ctrlPort
                    if ($res) {
                        $viewerProc = $res.proc
                        $viewerIsBrowser = $res.isBrowser
                        $activeKey = $key
                    }
                }
            }
        } else {
            if (($now - $lastStatusTime).TotalSeconds -ge 4 -and $activeKey -eq "") {
                $lastStatusTime = $now
                Write-Host "[DETECTADO EN ESPERA] $($detected.device) ($($detected.ip)) - Pulsa 'Transmitir' en el movil..." -ForegroundColor Cyan
            }
        }
    } else {
        if ($activeKey -ne "") {
            $missCount++
            if ($missCount -ge 4) {
                Write-Host "[INFO] Transmision detenida. Esperando nueva conexion..." -ForegroundColor Yellow
                $activeKey = ""
                $viewerProc = $null
                $viewerIsBrowser = $false
                $missCount = 0
            }
        }

        $now = Get-Date
        if (($now - $lastStatusTime).TotalSeconds -ge 4 -and $activeKey -eq "") {
            $lastStatusTime = $now
            if ($gateways.Count -eq 0 -and $adbSerials.Count -eq 0) {
                Write-Host "[Esperando enlace USB...] No hay 'Anclaje USB' ni ADB activo aun." -ForegroundColor DarkYellow
            } else {
                Write-Host "[Listo] Buscando transmision activa en: $($gateways -join ', ') ..." -ForegroundColor DarkGray
            }
        }
    }

    Start-Sleep -Milliseconds 650
}
