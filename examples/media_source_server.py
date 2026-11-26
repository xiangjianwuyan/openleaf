#!/usr/bin/env python3
"""OpenLeaf reference bridge; stdlib only. Default backend is original synthetic media.

Optional upstreams implement the HTTP shapes documented in the referenced Venera
minimal_source.js and XY animemusic example. This does not execute JS plugins.
"""
import argparse
import io
import json
import math
import ssl
import struct
import threading
import urllib.parse
import urllib.request
import wave
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def png(page):
    width, height = 480, 640
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            edge = x < 12 or x >= width-12 or y < 12 or y >= height-12
            panel = 45 < x < 435 and 80 < y < 530
            rows.extend((35, 45, 55) if edge else ((80+page*30, 135, 160) if panel else (246, 244, 238)))
    def chunk(kind, content):
        return struct.pack('>I', len(content))+kind+content+struct.pack('>I', zlib.crc32(kind+content)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(rows))+chunk(b'IEND',b'')


def wav():
    output = io.BytesIO()
    with wave.open(output,'wb') as out:
        out.setnchannels(1); out.setsampwidth(2); out.setframerate(16000)
        out.writeframes(b''.join(struct.pack('<h',int(2500*math.sin(2*math.pi*220*i/16000))) for i in range(16000*3)))
    return output.getvalue()


def checked_base(value):
    u=urllib.parse.urlsplit(value)
    if u.scheme!='https' or not u.hostname or u.username or u.password or u.fragment:
        raise ValueError('upstream/public URL must be HTTPS without credentials or fragment')
    return value.rstrip('/')


def fetch(base,path,params):
    url=checked_base(base)+'/'+path.lstrip('/')+'?'+urllib.parse.urlencode(params)
    with urllib.request.urlopen(urllib.request.Request(url,headers={'Accept':'application/json'}),timeout=15) as response:
        if urllib.parse.urlsplit(response.url).scheme!='https':
            raise ValueError('upstream redirected to non-HTTPS URL')
        raw=response.read(2*1024*1024+1)
        if len(raw)>2*1024*1024: raise ValueError('upstream response exceeds 2 MiB')
        return json.loads(raw)


def pack(public):
    return {'format':'openleaf-media-sources','schemaVersion':1,'sources':[
        {'id':'reference-comics','name':'参考漫画接口','kind':'comics','baseUrl':public+'/',
         'search':'comics/search?q={query}&page={page}','detail':'comics/detail?id={id}','pages':'comics/pages?id={id}'},
        {'id':'reference-music','name':'参考音乐接口','kind':'music','baseUrl':public+'/',
         'search':'music/search?q={query}&page={page}','stream':'music/stream?id={id}'}]}


class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        try:
            parsed=urllib.parse.urlsplit(self.path); args=urllib.parse.parse_qs(parsed.query)
            get=lambda key,default='': args.get(key,[default])[0]
            path=parsed.path.strip('/'); public=self.server.public
            if path=='source-pack.json': return self.reply(pack(public))
            if path=='media/tone.wav': return self.reply(wav(),'audio/wav')
            if path in ('media/page-1.png','media/page-2.png'): return self.reply(png(1 if '1' in path else 2),'image/png')
            page=max(1,int(get('page','1'))); identity=get('id'); q=get('q')
            if path=='comics/search':
                if self.server.venera:
                    data=fetch(self.server.venera,'search',{'q':q,'page':page})
                    items=[{'id':str(x['id']),'title':x['title'],'author':x.get('author','')} for x in data['items']]
                    return self.reply({'items':items,'hasMore':page<int(data.get('totalPages',page))})
                return self.reply({'items':[{'id':'original-comic','title':'原创接口演示漫画','author':'OpenLeaf fixture'}] if page==1 else [],'hasMore':False})
            if path=='comics/detail':
                if self.server.venera:
                    data=fetch(self.server.venera,'comics/'+urllib.parse.quote(identity,safe=''),{})
                    return self.reply({'title':data['title'],'description':data.get('description',''),
                        'chapters':[{'id':identity+'|'+str(c['id']),'title':c['title']} for c in data['chapters']]})
                if identity!='original-comic': return self.reply({'error':'not found'},code=404)
                return self.reply({'title':'原创接口演示漫画','description':'由测试服务实时生成的两页几何图形，用于接口验收。','chapters':[{'id':'original-comic|chapter-1','title':'第一章 · 两页演示'}]})
            if path=='comics/pages':
                if self.server.venera:
                    comic,chapter=identity.split('|',1)
                    data=fetch(self.server.venera,'comics/'+urllib.parse.quote(comic,safe='')+'/chapters/'+urllib.parse.quote(chapter,safe=''),{})
                    return self.reply({'pages':data['images']})
                if identity!='original-comic|chapter-1': return self.reply({'error':'not found'},code=404)
                return self.reply({'pages':[public+'/media/page-1.png',public+'/media/page-2.png']})
            if path=='music/search':
                if self.server.xy:
                    data=fetch(self.server.xy,'music/search',{'platform':self.server.platform,'keyword':q,'page':page,'limit':30})
                    if data.get('code')!=200: raise ValueError(data.get('message','music API error'))
                    items=[{'id':str(x['id']),'title':x['title'],'artist':x.get('artist','')} for x in data.get('data',[])]
                    return self.reply({'items':items,'hasMore':len(items)>=30 and not data.get('isEnd',False)})
                return self.reply({'items':[{'id':'original-tone','title':'原创 220 Hz 测试音','artist':'OpenLeaf fixture'}] if page==1 else [],'hasMore':False})
            if path=='music/stream':
                if self.server.xy:
                    data=fetch(self.server.xy,'music/url',{'source':self.server.platform,'musicId':identity,'quality':'320k'})
                    if data.get('code')!=200: raise ValueError(data.get('message','music API error'))
                    checked_base(data['url'])
                    return self.reply({'url':data['url']})
                if identity!='original-tone': return self.reply({'error':'not found'},code=404)
                return self.reply({'url':public+'/media/tone.wav'})
            self.reply({'error':'unknown endpoint'},code=404)
        except Exception as error:
            self.reply({'error':str(error)},code=502)
    def reply(self,value,mime='application/json',code=200):
        raw=value if isinstance(value,bytes) else json.dumps(value,ensure_ascii=False).encode()
        self.send_response(code);self.send_header('Content-Type',mime);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)


def server(host,port,public,venera='',xy='',platform='wy'):
    s=ThreadingHTTPServer((host,port),Handler)
    s.public=public;s.venera=venera;s.xy=xy;s.platform=platform
    return s


def self_test():
    s=server('127.0.0.1',0,'');s.public='http://127.0.0.1:'+str(s.server_port)
    thread=threading.Thread(target=s.serve_forever,daemon=True);thread.start()
    def get(path):
        with urllib.request.urlopen(s.public+'/'+path,timeout=5) as response: return response.read()
    try:
        assert len(json.loads(get('source-pack.json'))['sources'])==2
        comic=json.loads(get('comics/search?q=test&page=1'))['items'][0]
        detail=json.loads(get('comics/detail?'+urllib.parse.urlencode({'id':comic['id']})))
        pages=json.loads(get('comics/pages?'+urllib.parse.urlencode({'id':detail['chapters'][0]['id']})))['pages']
        assert len(pages)==2
        for url in pages:
            with urllib.request.urlopen(url) as r: assert r.read().startswith(b'\x89PNG')
        song=json.loads(get('music/search?q=test&page=1'))['items'][0]
        stream=json.loads(get('music/stream?'+urllib.parse.urlencode({'id':song['id']})))['url']
        with urllib.request.urlopen(stream) as r: assert r.read().startswith(b'RIFF')
        assert json.loads(get('music/search?q=test&page=2'))['items']==[]
        print('PASS: fixture HTTP search/detail/pages/images and search/stream/WAV; source pack and pagination')
    finally:
        s.shutdown();s.server_close();thread.join(timeout=3)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host',default='127.0.0.1');parser.add_argument('--port',type=int,default=18765)
    parser.add_argument('--public-base',default='https://adapter.example:18765')
    parser.add_argument('--cert');parser.add_argument('--key');parser.add_argument('--http',action='store_true',help='local protocol tests only; Android app requires HTTPS')
    parser.add_argument('--venera-api',default='',help='authorized API matching Venera minimal_source.js HTTP shape')
    parser.add_argument('--xy-api',default='',help='authorized API matching XY animemusic example path endpoints')
    parser.add_argument('--platform',default='wy');parser.add_argument('--self-test',action='store_true')
    options=parser.parse_args()
    if options.self_test: self_test()
    else:
        public=checked_base(options.public_base)
        for upstream in (options.venera_api,options.xy_api):
            if upstream: checked_base(upstream)
        s=server(options.host,options.port,public,options.venera_api,options.xy_api,options.platform)
        if not options.http:
            if not options.cert or not options.key: parser.error('HTTPS mode requires --cert and --key trusted by client')
            context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(options.cert,options.key);s.socket=context.wrap_socket(s.socket,server_side=True)
        print('OpenLeaf media adapter listening; source pack: '+public+'/source-pack.json',flush=True)
        try: s.serve_forever()
        except KeyboardInterrupt: pass
        finally: s.server_close()
