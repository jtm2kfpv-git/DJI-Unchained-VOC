"""Compare the embedded N3 packets with a pinned dji_protocol checkout."""

from __future__ import annotations

import argparse
import ast
import re
from pathlib import Path


def upstream_packets(source: Path) -> list[bytes]:
    tree = ast.parse(source.read_text(encoding="utf-8"))
    assignment = next(
        node
        for node in tree.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "MAGIC_PACKETS" for target in node.targets)
    )
    return [bytes(packet) for packet in ast.literal_eval(assignment.value)]


def embedded_packets(source: Path) -> list[bytes]:
    text = source.read_text(encoding="utf-8")
    blocks = re.findall(r"parseHex\((.*?)\)", text, re.DOTALL)[:2]
    return [bytes.fromhex("".join(re.findall(r'"([0-9a-f]+)"', block))) for block in blocks]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("upstream", type=Path)
    args = parser.parse_args()

    expected = upstream_packets(args.upstream / "scripts" / "video_out_mobile.py")
    actual = embedded_packets(
        Path(__file__).resolve().parents[1]
        / "app"
        / "src"
        / "main"
        / "java"
        / "local"
        / "n3view"
        / "voc"
        / "protocol"
        / "N3ControlPackets.java"
    )
    if actual != expected:
        raise SystemExit("Embedded N3 control packets differ from pinned upstream")
    print(f"byte-exact match: {len(actual)} packets, lengths {[len(packet) for packet in actual]}")


if __name__ == "__main__":
    main()
