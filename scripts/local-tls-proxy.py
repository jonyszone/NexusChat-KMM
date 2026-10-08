"""Loopback-only TLS test proxy; never a production reverse proxy."""

import argparse
import select
import socket
import socketserver
import ssl


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--certificate", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--port", type=int, default=8443)
    args = parser.parse_args()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.certificate, args.key)
    context.set_alpn_protocols(["http/1.1"])

    class Proxy(socketserver.BaseRequestHandler):
        def handle(self):
            self.request.settimeout(5)
            try:
                with context.wrap_socket(self.request, server_side=True) as client:
                    with socket.create_connection(("127.0.0.1", 3000), timeout=5) as backend:
                        # Forward bytes unchanged, including HTTP upgrades and WS frames.
                        while True:
                            wait = 0 if client.pending() else 30
                            readable, _, _ = select.select([client, backend], [], [], wait)
                            if client.pending() and client not in readable:
                                readable.append(client)
                            for source in readable:
                                data = source.recv(65536)
                                if not data:
                                    return
                                target = backend if source is client else client
                                target.sendall(data)
            except (OSError, ssl.SSLError):
                # Negative trust/hostname tests deliberately abort TLS handshakes.
                return

    class Server(socketserver.ThreadingTCPServer):
        daemon_threads = True
        request_queue_size = 16

    with Server(("127.0.0.1", args.port), Proxy) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
