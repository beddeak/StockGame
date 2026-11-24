"""로그인 없이 Minecraft 상태 프로토콜로 실제 접속 응답을 확인합니다. 플레이어 접속 검증과는 구분합니다."""
import argparse
import io
import json
import socket
import struct

def varint(value):
    result = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        result.append(byte | (128 if value else 0))
        if not value:
            return bytes(result)

def read_varint(stream):
    value = 0
    for offset in range(5):
        raw = stream.read(1)
        if not raw:
            raise EOFError("Incomplete status packet")
        byte = raw[0]
        value |= (byte & 127) << (7 * offset)
        if not byte & 128:
            return value
    raise ValueError("Invalid VarInt")

def status(host, port):
    with socket.create_connection((host, port), timeout=5) as connection:
        encoded_host = host.encode("utf-8")
        handshake = b"\0" + varint(766) + varint(len(encoded_host)) + encoded_host + struct.pack(">H", port) + b"\1"
        connection.sendall(varint(len(handshake)) + handshake + b"\1\0")
        with connection.makefile("rb") as response:
            length = read_varint(response)
            if not 0 < length < 1048576:
                raise ValueError("Status packet length out of range")
            payload = io.BytesIO(response.read(length))
            if read_varint(payload) != 0:
                raise ValueError("Unexpected packet ID")
            size = read_varint(payload)
            result = json.loads(payload.read(size).decode("utf-8"))
            return {"version": result["version"], "players": result["players"], "description": result["description"]}

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25565)
    args = parser.parse_args()
    print(json.dumps(status(args.host, args.port), ensure_ascii=False, indent=2))
