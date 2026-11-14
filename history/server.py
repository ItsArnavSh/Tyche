"""Tiny stdlib HTTP server for History mode.

Keeping the transport dependency-free means the history service can run with
the same Python environment as the backtester (pandas/numpy/requests) and in a
small Docker image.  The dashboard only needs JSON over HTTP.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from backtest.data import DataError

from .service import HistoryApp

logger = logging.getLogger("tyche.history")


class HistoryHandler(BaseHTTPRequestHandler):
    server_version = "TycheHistory/1.0"

    @property
    def app(self) -> HistoryApp:
        return self.server.app  # type: ignore[attr-defined]

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(HTTPStatus.NO_CONTENT)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET(head_only=True)

    def do_GET(self, head_only: bool = False) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        try:
            if path in {"/healthz", "/api/history/health"}:
                self._json(HTTPStatus.OK, self.app.health(), head_only=head_only)
            elif path == "/api/history/strategies":
                self._json(HTTPStatus.OK, self.app.strategies(), head_only=head_only)
            elif path == "/api/history/cache":
                self._json(HTTPStatus.OK, self.app.cache.health(), head_only=head_only)
            elif path == "/":
                self._json(
                    HTTPStatus.OK,
                    {
                        "service": "tyche-history",
                        "health": "/api/history/health",
                        "fetch": "POST /api/history/fetch",
                        "replay": "POST /api/history/replay",
                        "strategies": "/api/history/strategies",
                    },
                    head_only=head_only,
                )
            else:
                self._json(
                    HTTPStatus.NOT_FOUND,
                    {"error": f"unknown path {path}"},
                    head_only=head_only,
                )
        except Exception as exc:  # pragma: no cover - defensive
            logger.exception("GET %s failed", path)
            self._json(
                HTTPStatus.INTERNAL_SERVER_ERROR,
                {"error": str(exc)},
                head_only=head_only,
            )

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0].rstrip("/")
        if path not in {"/api/history/fetch", "/api/history/replay", "/api/history/cache/clear"}:
            self._json(HTTPStatus.NOT_FOUND, {"error": f"unknown path {path}"})
            return
        try:
            payload = self._read_json()
            if path == "/api/history/fetch":
                result = self.app.fetch(payload)
            elif path == "/api/history/replay":
                result = self.app.replay(payload)
            else:
                result = self.app.clear_cache()
            self._json(HTTPStatus.OK, result)
        except ValueError as exc:
            self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
        except DataError as exc:
            self._json(HTTPStatus.BAD_GATEWAY, {"error": str(exc)})
        except Exception as exc:
            logger.exception("POST %s failed", path)
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": str(exc)})

    # ---------------------------------------------------------------- plumbing

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if length > 2 * 1024 * 1024:
            raise ValueError("request body too large")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"invalid JSON body: {exc}") from exc
        if not isinstance(payload, dict):
            raise ValueError("request body must be a JSON object")
        return payload

    def _json(
        self,
        status: HTTPStatus,
        payload: Any,
        *,
        head_only: bool = False,
    ) -> None:
        body = json.dumps(payload, allow_nan=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self._cors()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if not head_only:
            self.wfile.write(body)

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def log_message(self, fmt: str, *args: Any) -> None:
        logger.info("%s - %s", self.address_string(), fmt % args)


class HistoryServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: tuple[str, int], app: HistoryApp) -> None:
        self.app = app
        super().__init__(address, HistoryHandler)


def serve(app: HistoryApp, host: str = "0.0.0.0", port: int = 8090) -> None:
    server = HistoryServer((host, port), app)
    logger.info("history service listening on http://%s:%s", host, port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logger.info("history service stopping")
    finally:
        server.server_close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Tyche History replay API")
    parser.add_argument(
        "--host", default=os.getenv("HISTORY_HOST", "0.0.0.0")
    )
    parser.add_argument(
        "--port", type=int, default=int(os.getenv("HISTORY_PORT", "8090"))
    )
    args = parser.parse_args(argv)
    logging.basicConfig(
        level=os.getenv("HISTORY_LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    app = HistoryApp(
        max_workers=int(os.getenv("HISTORY_MAX_WORKERS", "8")),
        timeout=float(os.getenv("HISTORY_FETCH_TIMEOUT", "15")),
        retries=int(os.getenv("HISTORY_FETCH_RETRIES", "3")),
    )
    try:
        serve(app, args.host, args.port)
    except OSError as exc:
        logger.error("could not start history service: %s", exc)
        return 1
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
