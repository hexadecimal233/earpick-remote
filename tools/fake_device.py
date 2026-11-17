#!/usr/bin/env python3
"""Fake zdeer / UDU otoscope for local development.

Serves the recovered UDP protocol on port 40000: replies to heartbeats with a
device report and streams a JPEG as 1400-byte fragments.

Usage:
    python3 tools/fake_device.py                     # listen on 0.0.0.0:40000
    python3 tools/fake_device.py --frame my.jpg --fps 15

Point the debug build at the machine running this script:
  * emulator: developer override host 10.0.2.2, port 40000
  * phone on the same Wi-Fi: host = this machine's LAN IP, port 40000
"""

from __future__ import annotations

import argparse
import select
import socket
import struct
import sys
import time
from pathlib import Path

MAGIC = b"\x63\x63"
HEADER = 7
VIDEO_HEADER = 0x36
MAX_PAYLOAD = 1400

TYPE_REPORT = 0x01
TYPE_STOP = 0x02
TYPE_VIDEO = 0x03
TYPE_MIRROR = 0x05
TYPE_LED_ON = 0x0F
TYPE_LED_OFF = 0x10

TYPE_NAMES = {
    TYPE_REPORT: "heartbeat",
    TYPE_STOP: "stop-stream",
    TYPE_VIDEO: "video",
    TYPE_MIRROR: "mirror",
    TYPE_LED_ON: "led-on",
    TYPE_LED_OFF: "led-off",
}


def report(ssid: str, packet_type: int, seq: int) -> bytes:
    name = ssid.encode("utf-8")[:98]
    payload = name + b"\x00" * (99 - len(name))
    return MAGIC + bytes([packet_type]) + struct.pack("<HH", seq, 0x63) + payload


def jpeg_size(data: bytes) -> tuple[int, int]:
    """Read width/height from the first SOF marker."""
    index = 2
    while index + 9 < len(data):
        if data[index] != 0xFF:
            index += 1
            continue
        marker = data[index + 1]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3):
            height, width = struct.unpack_from(">HH", data, index + 5)
            return width, height
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            index += 2
            continue
        segment = struct.unpack_from(">H", data, index + 2)[0]
        index += 2 + segment
    return 640, 480


def video_packet(
    jpeg: bytes,
    offset: int,
    length: int,
    seq: int,
    frame_index: int,
    width: int,
    height: int,
) -> bytes:
    payload = jpeg[offset : offset + length]
    packet = bytearray(VIDEO_HEADER + len(payload))
    packet[0:2] = MAGIC
    packet[2] = TYPE_VIDEO
    struct.pack_into("<H", packet, 0x03, seq)
    struct.pack_into("<H", packet, 0x05, len(packet))
    packet[0x07] = 0x03
    struct.pack_into("<I", packet, 0x0C, offset)
    struct.pack_into("<I", packet, 0x10, 73)  # raw battery ADC observed on hardware
    struct.pack_into("<H", packet, 0x1C, width)
    struct.pack_into("<H", packet, 0x1E, height)
    struct.pack_into("<H", packet, 0x30, frame_index)
    struct.pack_into("<H", packet, 0x32, frame_index + 1)
    struct.pack_into("<H", packet, 0x34, len(payload))
    packet[VIDEO_HEADER:] = payload
    return bytes(packet)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fake earpick UDP camera")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=40000)
    parser.add_argument("--frame", type=Path, default=Path(__file__).with_name("testcard.jpg"))
    parser.add_argument("--ssid", default="zdeer earpick-3593")
    parser.add_argument("--fps", type=float, default=20.0)
    args = parser.parse_args()

    jpeg = args.frame.read_bytes()
    if not (jpeg.startswith(b"\xff\xd8") and jpeg.endswith(b"\xff\xd9")):
        print(f"{args.frame} is not a baseline JPEG", file=sys.stderr)
        return 2
    width, height = jpeg_size(jpeg)

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((args.host, args.port))
    print(f"fake earpick listening on {args.host}:{args.port}, {len(jpeg)} B frame {width}x{height}, {args.fps:g} fps")
    print("emulator: debug override 10.0.2.2:40000; phone: use this machine's LAN IP")

    client: tuple[str, int] | None = None
    seq = 0
    next_frame_at = time.monotonic()
    frame_interval = 1.0 / max(args.fps, 0.1)
    frame_index = 0

    try:
        while True:
            timeout = max(0.0, next_frame_at - time.monotonic())
            readable, _, _ = select.select([sock], [], [], timeout)
            if readable:
                data, address = sock.recvfrom(4096)
                if len(data) >= HEADER and data[0:2] == MAGIC:
                    packet_type = data[2]
                    seq_in = struct.unpack_from("<H", data, 3)[0]
                    client = address
                    name = TYPE_NAMES.get(packet_type, f"0x{packet_type:02x}")
                    print(f"<- {name} seq={seq_in} from {address[0]}:{address[1]}")
                    if packet_type != TYPE_VIDEO:
                        sock.sendto(report(args.ssid, packet_type, seq_in), address)

            now = time.monotonic()
            if client is not None and now >= next_frame_at:
                offset = 0
                index = 1
                while offset < len(jpeg):
                    length = min(MAX_PAYLOAD, len(jpeg) - offset)
                    sock.sendto(
                        video_packet(jpeg, offset, length, seq, index, width, height),
                        client,
                    )
                    offset += length
                    index += 1
                    seq = (seq + 1) & 0xFFFF
                frame_index += 1
                next_frame_at = now + frame_interval
    except KeyboardInterrupt:
        print("\nstopped")
    finally:
        sock.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
