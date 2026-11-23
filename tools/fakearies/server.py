#!/usr/bin/env python3
"""Fake AURALiC Aries for the automatic test: web page (80), service list (9998) and settings services (SOAP)."""
import html, os, re, sys, threading
from http.server import HTTPServer, SimpleHTTPRequestHandler, BaseHTTPRequestHandler

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(sys.argv[1] if len(sys.argv) > 1 else '.', 'SET-LOGG.txt')
RENDER_PORT, SERVER_PORT = 45420, 38949

DEVICES = f'''<?xml version="1.0" encoding="UTF-8"?>
<root>
    <device_name>Aries (test)</device_name>
    <device_sn>TEST0001</device_sn>
    <device type="Server"><udn>lightningServer-test</udn><port>{SERVER_PORT}</port>
        <service name="ServerConfig"><action type="get">GetServerConfig</action><action type="set">SetServerConfig</action><property>SubscriptValue</property></service>
    </device>
    <device type="Renderer"><udn>lightningRender-test</udn><port>{RENDER_PORT}</port>
        <service name="WebRendererConfig"><action type="get">GetRendererConfig</action><action type="set">SetRendererConfig</action><property>RendererConfig</property></service>
        <service name="WebProcessorConfig"><action type="get">GetProcessorConfig</action><action type="set">SetProcessorConfig</action><property>ProcessorConfig</property></service>
        <service name="WebDeviceConfig"><action type="get">GetDeviceConfig</action><action type="set">SetDeviceConfig</action><property>DeviceConfig</property></service>
    </device>
</root>'''

PROPS = {'ServerConfig': 'ServerConfig', 'WebRendererConfig': 'RendererConfig', 'WebProcessorConfig': 'ProcessorConfig', 'WebDeviceConfig': 'DeviceConfig'}


class Devices(BaseHTTPRequestHandler):
    def do_GET(self):
        b = DEVICES.encode()
        self.send_response(200); self.send_header('Content-Type', 'text/xml'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)


class Soap(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8', 'replace')
        m = re.search(r'service:(\w+):1#(\w+)', self.headers.get('SOAPAction', ''))
        svc, act = (m.group(1), m.group(2)) if m else ('?', '?')
        urn = f'urn:av-openhome-org:service:{svc}:1'
        if act.startswith('Get'):
            path = os.path.join(HERE, 'config', act + '.xml')
            inner = f'<{PROPS.get(svc, "Value")}>{html.escape(open(path).read())}</{PROPS.get(svc, "Value")}>' if os.path.exists(path) else ''
        else:
            with open(LOG, 'a') as f: f.write(f'{svc} {act}: {html.unescape(body)}\n')
            inner = ''
        out = (f'<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
               f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{act}Response xmlns:u="{urn}">{inner}'
               f'</u:{act}Response></s:Body></s:Envelope>').encode()
        self.send_response(200); self.send_header('Content-Type', 'text/xml'); self.send_header('Content-Length', str(len(out))); self.end_headers(); self.wfile.write(out)


class Web(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=HERE, **k)


def run(port, h): HTTPServer(('0.0.0.0', port), h).serve_forever()

for port, h in [(80, Web), (9998, Devices), (RENDER_PORT, Soap), (SERVER_PORT, Soap)]:
    threading.Thread(target=run, args=(port, h), daemon=True).start()
threading.Event().wait()
