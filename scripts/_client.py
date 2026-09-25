"""Tiny HTTP helper shared by the verification scripts.

Standard library only -- no requests, no pytest. That keeps the scripts
runnable on a clean machine with nothing but Python 3 and psql installed,
which matters because these are the evidence behind the correctness claims
in ARCHITECTURE.md.
"""
import http.client
import json
import subprocess

HOST, PORT = "localhost", 8080


def new_conn():
    # A reusable connection. Worth it when a script fires hundreds of requests:
    # a fresh TCP connection per call makes the scan loop several times slower.
    return http.client.HTTPConnection(HOST, PORT, timeout=60)


def call(conn, method, path, body=None):
    """One request on an existing connection. Returns (status, parsed_json)."""
    payload = json.dumps(body) if body is not None else None
    conn.request(method, path, payload, {"Content-Type": "application/json"})
    resp = conn.getresponse()
    raw = resp.read()
    return resp.status, (json.loads(raw) if raw else {})


def req(method, path, body=None):
    """One request on a throwaway connection, for low-volume calls."""
    conn = new_conn()
    try:
        return call(conn, method, path, body)
    finally:
        conn.close()


def sql(query, db="self_checkout"):
    """Single scalar/row query straight through psql.

    We check the DATABASE directly rather than trusting the API, because the
    whole point of these tests is to catch the case where the API happily
    returns 200 while the stored data is wrong.
    """
    # -t = no header row, -A = unaligned, so the output is easy to compare
    # against an expected string.
    out = subprocess.run(["psql", db, "-tAc", query], capture_output=True, text=True)
    return out.stdout.strip()


def check(label, actual, expected):
    # Prints one PASS/FAIL line and returns a bool, so callers can AND them
    # together and exit non-zero if anything failed.
    ok = actual == expected
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}: got {actual!r}, expected {expected!r}")
    return ok
