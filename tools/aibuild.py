#!/usr/bin/env python3
"""Talk to AIBuild's control port.

The port speaks one JSON object per line, so this is only a convenience: netcat
works just as well. Enable the port first by setting controlPortEnabled to true
in config/aibuild.json and running /aibuild reload.

Examples:
    python tools/aibuild.py ping
    python tools/aibuild.py status
    python tools/aibuild.py ask "a 2x2 piston door"
    python tools/aibuild.py plan "a small stone hut"
    python tools/aibuild.py prompt "a cactus farm with a chest"
    python tools/aibuild.py inspect 120 64 -30
    python tools/aibuild.py blocks glass_pane
    python tools/aibuild.py undo
    python tools/aibuild.py place plan.json
    python tools/aibuild.py raw '{"cmd":"prompt","text":"a bridge","facing":"north"}'
"""
from __future__ import annotations

import argparse
import json
import socket
import sys

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 25585


def send(request: dict, host: str, port: int, token: str | None, timeout: float) -> dict:
    """Sends one request and returns the single response object."""
    if token:
        request["token"] = token

    with socket.create_connection((host, port), timeout=timeout) as connection:
        connection.sendall((json.dumps(request) + "\n").encode("utf-8"))

        # The reply is one line, but a large plan arrives in several packets.
        buffer = b""
        while b"\n" not in buffer:
            chunk = connection.recv(65536)
            if not chunk:
                break
            buffer += chunk

    line = buffer.split(b"\n", 1)[0].decode("utf-8", errors="replace")
    if not line:
        raise SystemExit("the control port closed without replying")
    return json.loads(line)


def build_request(args: argparse.Namespace) -> dict:
    command = args.command

    if command == "raw":
        if not args.rest:
            raise SystemExit("raw needs a JSON object, e.g. raw '{\"cmd\":\"ping\"}'")
        return json.loads(args.rest[0])

    if command in ("ask", "plan", "prompt"):
        if not args.rest:
            raise SystemExit(f"{command} needs some text, e.g. {command} \"a small hut\"")
        request = {"cmd": command, "text": " ".join(args.rest)}
        if args.player:
            request["player"] = args.player
        if args.facing:
            request["facing"] = args.facing
        return request

    if command == "place":
        if not args.rest:
            raise SystemExit("place needs a path to a plan JSON file")
        with open(args.rest[0], encoding="utf-8") as handle:
            plan = json.load(handle)
        request = {"cmd": "place", "plan": plan}
        if args.player:
            request["player"] = args.player
        if args.facing:
            request["facing"] = args.facing
        return request

    if command == "inspect":
        if len(args.rest) < 3:
            raise SystemExit("inspect needs x y z")
        x, y, z = (int(value) for value in args.rest[:3])
        return {"cmd": "inspect", "x": x, "y": y, "z": z}

    if command == "blocks":
        request = {"cmd": "blocks", "query": args.rest[0] if args.rest else ""}
        if args.limit:
            request["limit"] = args.limit
        return request

    if command in ("undo", "cancel"):
        request = {"cmd": command}
        if args.player:
            request["player"] = args.player
        return request

    # ping, status, players, help
    return {"cmd": command}


def main() -> int:
    parser = argparse.ArgumentParser(
        prog="aibuild",
        description="Send a command to AIBuild's control port.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("command", help="ping, status, players, help, ask, plan, prompt, "
                                        "place, undo, cancel, inspect, blocks, raw")
    parser.add_argument("rest", nargs="*", help="arguments for the command")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--token", default=None, help="only needed if controlToken is set")
    parser.add_argument("--player", default=None, help="whose position to build at")
    parser.add_argument("--facing", default=None, choices=["north", "south", "east", "west"])
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--timeout", type=float, default=300.0,
                        help="seconds to wait, since a model call is slow")
    parser.add_argument("--compact", action="store_true", help="one line of JSON, no indenting")
    args = parser.parse_args()

    try:
        response = send(build_request(args), args.host, args.port, args.token, args.timeout)
    except ConnectionRefusedError:
        raise SystemExit(
            f"nothing listening on {args.host}:{args.port}. Set controlPortEnabled to true in "
            "config/aibuild.json, then run /aibuild reload and restart the world."
        )
    except socket.timeout:
        raise SystemExit(f"no reply within {args.timeout}s")

    print(json.dumps(response, indent=None if args.compact else 2))
    return 0 if response.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
