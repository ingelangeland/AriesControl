#!/usr/bin/env python3
"""Fake AURALiC Aries for the automatic test: web page (80), service list (9998) and settings services (SOAP)."""
import html, os, re, sys, threading, time
from http.server import ThreadingHTTPServer as HTTPServer, SimpleHTTPRequestHandler, BaseHTTPRequestHandler

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

PROPS = {'WiFiList': 'WiFiList', 'ServerConfig': 'ServerConfig', 'WebRendererConfig': 'RendererConfig', 'WebProcessorConfig': 'ProcessorConfig', 'WebDeviceConfig': 'DeviceConfig'}


class Devices(BaseHTTPRequestHandler):
    def do_GET(self):
        b = DEVICES.encode()
        self.send_response(200); self.send_header('Content-Type', 'text/xml'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)


OH_SERVICES = ['Product', 'Info', 'Time', 'Volume', 'Playlist']
DEVICE_XML = '<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><device>' \
    '<deviceType>urn:av-openhome-org:device:Source:1</deviceType><friendlyName>Aries (test)</friendlyName><UDN>uuid:lightningRender-test</UDN><serviceList>' + \
    ''.join(f'<service><serviceType>urn:av-openhome-org:service:{n}:1</serviceType><serviceId>urn:av-openhome-org:serviceId:{n}</serviceId>'
            f'<SCPDURL>/lightningRender-test/av.openhome.org-{n}-1/service.xml</SCPDURL><controlURL>/lightningRender-test/av.openhome.org-{n}-1/control</controlURL>'
            f'<eventSubURL>/lightningRender-test/av.openhome.org-{n}-1/event</eventSubURL></service>' for n in OH_SERVICES) + \
    '</serviceList></device></root>'
STATE = {'playing': True, 'volume': 40, 'mute': False, 'source': 0, 'standby': False, 'shuffle': False, 'repeat': False, 'id': 2, 't0': time.time()}
SOURCES = [('Playlist', 'Playlist'), ('Radio', 'Radio'), ('TIDAL Connect', 'TidalConnect'), ('AirPlay', 'AirPlay'), ('Roon Ready', 'RoonReady')]
TRACKS = {1: ('Morning Light', 'Test Quartet'), 2: ('Golden Hour', 'The Fake Aries Band'), 3: ('Night Drive', 'Test Quartet'), 4: ('Blue Room', 'Sample Trio')}


def didl(title, artist):
    return html.escape(f'<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">'
                       f'<item><dc:title>{title}</dc:title><upnp:artist>{artist}</upnp:artist><upnp:album>Test Album</upnp:album>'
                       f'<upnp:albumArtURI>http://10.0.2.2/art.png</upnp:albumArtURI></item></DIDL-Lite>')


def openhome(svc, act, body):
    st = STATE
    def arg(name):
        m = re.search(f'<{name}>(.*?)</{name}>', body, re.S)
        return html.unescape(m.group(1)) if m else ''
    secs = int(time.time() - st['t0']) % 245 if st['playing'] else 60
    if svc == 'Product':
        if act == 'SourceXml':
            x = '<SourceList>' + ''.join(f'<Source><Name>{n}</Name><Type>{t}</Type><Visible>true</Visible></Source>' for n, t in SOURCES) + '</SourceList>'
            return f'<Value>{html.escape(x)}</Value>'
        if act == 'SourceIndex': return f'<Value>{st["source"]}</Value>'
        if act == 'Standby': return f'<Value>{str(st["standby"]).lower()}</Value>'
        if act == 'SetSourceIndex': st['source'] = int(arg('Value') or 0)
        if act == 'SetStandby': st['standby'] = arg('Value') == 'true'
        return ''
    if svc == 'Info':
        t, a = TRACKS[st['id']]
        if act == 'Track': return f'<Uri>http://test/{st["id"]}.flac</Uri><Metadata>{didl(t, a)}</Metadata>'
        if act == 'Details': return '<Duration>245</Duration><BitRate>4608000</BitRate><BitDepth>24</BitDepth><SampleRate>96000</SampleRate><Lossless>true</Lossless><CodecName>FLAC</CodecName>'
        return ''
    if svc == 'Time':
        return f'<TrackCount>4</TrackCount><Duration>245</Duration><Seconds>{secs}</Seconds>'
    if svc == 'Volume':
        if act == 'Volume': return f'<Value>{st["volume"]}</Value>'
        if act == 'VolumeLimit': return '<Value>100</Value>'
        if act == 'Mute': return f'<Value>{str(st["mute"]).lower()}</Value>'
        if act == 'SetVolume': st['volume'] = int(arg('Value') or 0)
        if act == 'SetMute': st['mute'] = arg('Value') == 'true'
        return ''
    if svc == 'Playlist':
        if act == 'TransportState': return f'<Value>{"Playing" if st["playing"] else "Paused"}</Value>'
        if act == 'Play': st['playing'] = True
        if act == 'Pause': st['playing'] = False
        if act == 'Next': st['id'] = st['id'] % 4 + 1
        if act == 'Previous': st['id'] = (st['id'] - 2) % 4 + 1
        if act == 'SeekId': st['id'] = int(arg('Value') or 1)
        if act == 'Id': return f'<Value>{st["id"]}</Value>'
        if act == 'Shuffle': return f'<Value>{str(st["shuffle"]).lower()}</Value>'
        if act == 'Repeat': return f'<Value>{str(st["repeat"]).lower()}</Value>'
        if act == 'SetShuffle': st['shuffle'] = arg('Value') == 'true'
        if act == 'SetRepeat': st['repeat'] = arg('Value') == 'true'
        if act == 'IdArray':
            import base64, struct
            return '<Token>1</Token><Array>' + base64.b64encode(b''.join(struct.pack('>I', i) for i in TRACKS)).decode() + '</Array>'
        if act == 'ReadList':
            x = '<TrackList>' + ''.join(f'<Entry><Id>{i}</Id><Uri>http://test/{i}.flac</Uri><Metadata>{didl(t, a)}</Metadata></Entry>' for i, (t, a) in TRACKS.items()) + '</TrackList>'
            return f'<TrackList>{html.escape(x)}</TrackList>'
        return ''
    return ''


class Soap(BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_GET(self):
        if self.path.endswith('/Upnp/device.xml') and 'lightningRender' in self.path:
            b = DEVICE_XML.encode()
            self.send_response(200); self.send_header('Content-Type', 'text/xml'); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
        else:
            self.send_response(404); self.end_headers()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode('utf-8', 'replace')
        m = re.search(r'service:(\w+):1#(\w+)', self.headers.get('SOAPAction', ''))
        svc, act = (m.group(1), m.group(2)) if m else ('?', '?')
        urn = f'urn:av-openhome-org:service:{svc}:1'
        if svc in OH_SERVICES:
            if not (act.startswith('Get') or act in ('SourceXml', 'SourceIndex', 'Standby', 'Track', 'Details', 'Time', 'Volume', 'VolumeLimit', 'Mute', 'TransportState', 'Id', 'Shuffle', 'Repeat', 'IdArray', 'ReadList')):
                with open(LOG, 'a') as f: f.write(f'OH {svc} {act}\n')
            inner = openhome(svc, act, body)
        elif act.startswith('Get'):
            path = os.path.join(HERE, 'config', act + '.xml')
            tag = 'WiFiList' if act == 'GetWiFiList' else PROPS.get(svc, 'Value')
            inner = f'<{tag}>{html.escape(open(path).read())}</{tag}>' if os.path.exists(path) else ''
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
