#!/usr/bin/env python3
"""In-process multi-node mesh simulation demo (no radio). Run: python3 tools/mesh_sim_demo.py"""

from __future__ import annotations
import time
from dataclasses import dataclass
from collections import deque
from typing import Deque, Dict, List, Optional, Set

DEFAULT_MAX_QUEUE = 256

@dataclass
class Envelope:
    message_id: bytes
    destination_token: bytes
    ttl_hops: int
    expires_at: float
    payload: bytes

    def is_expired(self, now: float) -> bool:
        return now >= self.expires_at

    def can_forward(self) -> bool:
        return self.ttl_hops > 0

class RelayQueue:
    def __init__(self, max_size: int = DEFAULT_MAX_QUEUE):
        self.max_size = max(1, max_size)
        self.queue: Deque[Envelope] = deque()
        self.seen: Set[bytes] = set()

    def enqueue(self, env: Envelope, now: float) -> bool:
        if env.is_expired(now) or not env.can_forward() or env.message_id in self.seen:
            return False
        while len(self.queue) >= self.max_size:
            old = self.queue.popleft()
            self.seen.discard(old.message_id)
        self.seen.add(env.message_id)
        self.queue.append(env)
        return True

    def dequeue_for_forward(self, now: float) -> Optional[Envelope]:
        while self.queue:
            env = self.queue.popleft()
            self.seen.discard(env.message_id)
            if env.is_expired(now) or not env.can_forward():
                continue
            env.ttl_hops -= 1
            return env
        return None

    def take_for_destination(self, token: bytes, now: float) -> List[Envelope]:
        result = []
        remaining = deque()
        while self.queue:
            env = self.queue.popleft()
            if env.destination_token == token and not env.is_expired(now):
                self.seen.discard(env.message_id)
                result.append(env)
            else:
                remaining.append(env)
        self.queue = remaining
        return result

def main() -> None:
    now = time.time()
    nodes: Dict[str, RelayQueue] = {"A": RelayQueue(), "B": RelayQueue(), "C": RelayQueue()}
    dest_c = b"\x0c" + b"\x00" * 15
    msg_id = b"\x2a" + b"\x00" * 15
    env = Envelope(message_id=msg_id, destination_token=dest_c, ttl_hops=4,
                   expires_at=now + 3600, payload=b"hello from A via B (python sim)")
    assert nodes["B"].enqueue(env, now)
    print(f"B queue size after A inject: {len(nodes['B'].queue)}")
    forwarded = nodes["B"].dequeue_for_forward(now)
    assert forwarded is not None
    print(f"Forwarded TTL now: {forwarded.ttl_hops}")
    assert nodes["C"].enqueue(forwarded, now)
    delivered = nodes["C"].take_for_destination(dest_c, now)
    assert len(delivered) == 1
    print(f"C received: {delivered[0].payload!r}")
    print("OK — A→B→C simulation succeeded")

if __name__ == "__main__":
    main()
