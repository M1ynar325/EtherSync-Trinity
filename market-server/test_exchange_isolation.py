#!/usr/bin/env python3
"""两个交易所之间的隔离测试（不依赖 Minecraft，直接打 HTTP API）。

启动两个独立 market-server 实例，验证：
1. 未认证请求返回 401
2. 各自货单互不可见
3. 心跳/服务器列表按交易所隔离
"""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

MARKET = Path(__file__).resolve().parent / "etherlink_market.py"


def wait_health(base: str, timeout: float = 8.0) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + "/health", timeout=1) as r:
                if r.status == 200:
                    return
        except Exception:
            time.sleep(0.2)
    raise RuntimeError(f"{base} 未能在 {timeout}s 内就绪")


def request(method: str, url: str, token: str | None = None, body: dict | None = None) -> tuple[int, dict]:
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        return e.code, json.loads(raw) if raw else {}


def main() -> int:
    procs: list[subprocess.Popen] = []
    tmp_a = Path(tempfile.mkdtemp(prefix="eslink-exchange-a-"))
    tmp_b = Path(tempfile.mkdtemp(prefix="eslink-exchange-b-"))
    port_a, port_b = 18765, 18766
    token_a, token_b = "token-a", "token-b"
    base_a, base_b = f"http://127.0.0.1:{port_a}", f"http://127.0.0.1:{port_b}"

    try:
        for name, base, tmp, port, token in (
            ("交易所A", base_a, tmp_a, port_a, token_a),
            ("交易所B", base_b, tmp_b, port_b, token_b),
        ):
            proc = subprocess.Popen(
                [
                    sys.executable, str(MARKET),
                    "--name", name,
                    "--port", str(port),
                    "--data", str(tmp),
                    "--host", "127.0.0.1",
                    "--token", token,
                    "--no-tui",
                ],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            procs.append(proc)

        wait_health(base_a)
        wait_health(base_b)

        # 1) 未认证必须 401
        status, body = request("GET", base_a + "/v1/listings")
        assert status == 401, f"未认证应返回 401，实际 {status}: {body}"

        # 2) 两个交易所都登记同一台 MC 服
        heartbeat = {
            "code": "ES2",
            "name": "以太物语",
            "blurb": "Create 机械动力 · 生存建筑",
            "color": "LIGHT_BLUE",
            "icon": "TERRACOTTA",
            "link_rate": 1.0,
        }
        for base, token in ((base_a, token_a), (base_b, token_b)):
            status, body = request("POST", base + "/v1/heartbeat", token, heartbeat)
            assert status == 200 and body.get("ok"), f"心跳失败 {base}: {status} {body}"

        # 3) 上架：A 上架钻石，B 上架铁锭
        listing_a = {
            "seller_uuid": "00000000-0000-0000-0000-0000000000a1",
            "seller_name": "Alex",
            "server_code": "ES2",
            "item_key": "minecraft:diamond",
            "item_name": "钻石",
            "amount": 8,
            "price": 48.0,
        }
        listing_b = {
            "seller_uuid": "00000000-0000-0000-0000-0000000000b2",
            "seller_name": "Steve",
            "server_code": "SNC",
            "item_key": "minecraft:iron_ingot",
            "item_name": "铁锭",
            "amount": 32,
            "price": 16.0,
        }
        status, body = request("POST", base_a + "/v1/listings", token_a, listing_a)
        assert status == 200 and body.get("ok"), f"A 上架失败: {status} {body}"
        status, body = request("POST", base_b + "/v1/listings", token_b, listing_b)
        assert status == 200 and body.get("ok"), f"B 上架失败: {status} {body}"

        # 4) A 里看不到 B 的货，B 里看不到 A 的货
        status, body_a = request("GET", base_a + "/v1/listings", token_a)
        status, body_b = request("GET", base_b + "/v1/listings", token_b)
        assert status == 200 and status == 200
        items_a = [x["item_key"] for x in body_a.get("listings", [])]
        items_b = [x["item_key"] for x in body_b.get("listings", [])]
        assert "minecraft:diamond" in items_a, f"A 应包含钻石: {items_a}"
        assert "minecraft:iron_ingot" not in items_a, f"A 不应包含铁锭: {items_a}"
        assert "minecraft:iron_ingot" in items_b, f"B 应包含铁锭: {items_b}"
        assert "minecraft:diamond" not in items_b, f"B 不应包含钻石: {items_b}"

        # 5) 服务器列表隔离：两个交易所都只有各自登记的一台服
        status, servers_a = request("GET", base_a + "/v1/servers", token_a)
        status, servers_b = request("GET", base_b + "/v1/servers", token_b)
        assert status == 200 and status == 200
        codes_a = [x["code"] for x in servers_a.get("servers", [])]
        codes_b = [x["code"] for x in servers_b.get("servers", [])]
        assert codes_a == ["ES2"], f"A 服务器列表异常: {codes_a}"
        assert codes_b == ["ES2"], f"B 服务器列表异常: {codes_b}"

        print("exchange isolation test passed")
        return 0
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()


if __name__ == "__main__":
    raise SystemExit(main())
