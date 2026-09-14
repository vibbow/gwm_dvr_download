#!/usr/bin/env python3
"""
电脑端下载脚本（也可用于验证协议）。先连上车机热点，然后：

    python gwm_dl.py --qr "https://app-down.gwm.com.cn/...haval_hotspot=..."
    python gwm_dl.py --host 192.168.176.214 --port 59352
"""
import argparse
import base64
import json
import os
import re
import socket
import sys
import time


def percent_decode(s: str) -> str:
    out = bytearray()
    i = 0
    while i < len(s):
        if s[i] == "%" and i + 2 < len(s):
            try:
                out.append(int(s[i + 1:i + 3], 16))
                i += 3
                continue
            except ValueError:
                pass
        out += s[i].encode()
        i += 1
    return out.decode("utf-8", errors="replace")


def parse_qr(text: str) -> dict:
    text = text.strip()
    if not text.startswith("haval://"):
        m = re.search(r"haval_hotspot=([^&#\s]+)", text)
        b64 = percent_decode(m.group(1) if m else text).replace("-", "+").replace("_", "/")
        b64 += "=" * (-len(b64) % 4)
        text = base64.b64decode(b64).decode()
    query = text.split("?", 1)[1] if "?" in text else ""
    params = {}
    for part in filter(None, query.split("&")):
        k, _, v = part.partition("=")
        params[k] = percent_decode(percent_decode(v))  # 原车 App 编码了两次
    return {
        "ssid": params.get("hotspotName"),
        "password": params.get("hotspotPassword"),
        "host": params["socketAddress"],
        "port": int(params["port"]),
    }


class Conn:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.buf = bytearray()

    def send(self, obj: str):
        self.sock.sendall(obj.encode() + b"\r\n")

    def _fill(self):
        chunk = self.sock.recv(1 << 20)
        if not chunk:
            raise EOFError("车机断开了连接")
        self.buf += chunk

    def read_json(self) -> dict:
        while True:
            idx = self.buf.find(b"\n")
            while idx < 0:
                self._fill()
                idx = self.buf.find(b"\n")
            line = bytes(self.buf[:idx]).strip()
            del self.buf[:idx + 1]
            if line:
                return json.loads(line)

    def read_exact_to(self, f, size: int, on_progress):
        received = 0
        while received < size:
            if not self.buf:
                self._fill()
            n = min(len(self.buf), size - received)
            f.write(self.buf[:n])
            del self.buf[:n]
            received += n
            on_progress(received)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--qr", help="二维码内容")
    ap.add_argument("--host")
    ap.add_argument("--port", type=int)
    ap.add_argument("--out", default="downloads")
    ap.add_argument("--phone-name", default="iPhone")
    args = ap.parse_args()

    if args.qr:
        cfg = parse_qr(args.qr)
        print(f"热点: {cfg['ssid']}  密码: {cfg['password']}  地址: {cfg['host']}:{cfg['port']}")
        host, port = cfg["host"], cfg["port"]
    else:
        host, port = args.host, args.port
    if not host or not port:
        ap.error("需要 --qr 或 --host/--port")

    os.makedirs(args.out, exist_ok=True)
    sock = socket.create_connection((host, port), timeout=10)
    sock.settimeout(300)
    conn = Conn(sock)
    conn.send(json.dumps({"requestCode": 1001, "phoneName": args.phone_name}, separators=(",", ":")))
    print("已连接，等待车机响应 ...")

    while True:
        msg = conn.read_json()
        code, status = msg.get("requestCode"), msg.get("statusCode")
        if code == 1008:
            print(f"请在车机上确认（{len(msg.get('fileList', []))} 个文件）")
        elif code == 1001 and status == 200:
            files = msg.get("fileList", [])
            break
        elif code == 1001:
            sys.exit(f"车机拒绝: statusCode={status}")
        else:
            print("忽略消息:", str(msg)[:200])

    print(f"共 {len(files)} 个文件")
    sock.settimeout(30)
    for i, f in enumerate(files):
        path = f["filePath"]
        name = path.rsplit("/", 1)[-1]
        conn.send(json.dumps({"filePath": path, "requestCode": 1002}, separators=(",", ":"), ensure_ascii=False))
        while True:
            hdr = conn.read_json()
            if hdr.get("requestCode") == 1002:
                break
        if hdr.get("statusCode") != 200:
            print(f"跳过 {name}: {hdr}")
            continue
        size = hdr.get("file", {}).get("fileSize", f["fileSize"])
        start = time.time()
        with open(os.path.join(args.out, name), "wb") as out:
            def progress(r):
                speed = r / max(time.time() - start, 1e-3) / 1048576
                print(f"\r[{i + 1}/{len(files)}] {name} {r * 100 // max(size, 1)}% {speed:.1f} MB/s", end="")
            conn.send(json.dumps({"filePath": path, "requestCode": 1003}, separators=(",", ":"), ensure_ascii=False))
            conn.read_exact_to(out, size, progress)
        conn.send(json.dumps({"filePath": path, "requestCode": 2001}, separators=(",", ":"), ensure_ascii=False))
        print()
    sock.close()
    print("完成")


if __name__ == "__main__":
    main()
