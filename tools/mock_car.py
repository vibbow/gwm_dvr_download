#!/usr/bin/env python3
"""
模拟车机，用于在没有车的情况下测试 App / gwm_dl.py：

    python mock_car.py 某个目录 --port 59352

手机和电脑在同一局域网时，App 勾选"已手动连上热点"，IP 填电脑的局域网 IP 即可。
"""
import argparse
import json
import os
import socket
import threading
import time


def send(conn, obj):
    conn.sendall(json.dumps(obj, separators=(",", ":")).encode() + b"\n")


def handle(conn, files, confirm_delay):
    buf = b""
    file_list = [
        {"fileName": os.path.splitext(os.path.basename(p))[0], "filePath": p,
         "fileSize": os.path.getsize(p), "id": i}
        for i, p in enumerate(files)
    ]
    total = sum(f["fileSize"] for f in file_list)
    try:
        while True:
            chunk = conn.recv(65536)
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if not line.strip():
                    continue
                req = json.loads(line)
                print("<-", req)
                if req["requestCode"] == 1001:
                    send(conn, {"fileList": file_list, "fileTotalLength": total, "requestCode": 1008, "statusCode": 0})
                    time.sleep(confirm_delay)  # 模拟用户在车机上确认
                    send(conn, {"fileList": file_list, "fileTotalLength": total, "requestCode": 1001, "statusCode": 200})
                elif req["requestCode"] == 1002:
                    f = next((x for x in file_list if x["filePath"] == req["filePath"]), None)
                    if f is None:
                        send(conn, {"requestCode": 1002, "statusCode": 404})
                        continue
                    send(conn, {"file": f, "fileTotalLength": 0, "requestCode": 1002, "statusCode": 200})
                elif req["requestCode"] == 1003:
                    f = next((x for x in file_list if x["filePath"] == req["filePath"]), None)
                    if f is None:
                        continue
                    with open(f["filePath"], "rb") as fh:
                        while data := fh.read(1 << 16):
                            conn.sendall(data)
                    print("->", f["fileName"], f["fileSize"], "bytes")
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir")
    ap.add_argument("--port", type=int, default=59352)
    ap.add_argument("--confirm-delay", type=float, default=2)
    args = ap.parse_args()
    files = sorted(os.path.abspath(os.path.join(args.dir, n)) for n in os.listdir(args.dir)
                   if os.path.isfile(os.path.join(args.dir, n)))
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", args.port))
    srv.listen()
    print(f"模拟车机监听 :{args.port}，共 {len(files)} 个文件")
    while True:
        conn, addr = srv.accept()
        print("连接:", addr)
        threading.Thread(target=handle, args=(conn, files, args.confirm_delay), daemon=True).start()


if __name__ == "__main__":
    main()
