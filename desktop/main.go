// 最小可用桌面客户端：UDP 收 MJPEG → 本地 HTTP 页面显示。
// 用法：./zdeer-client -device 192.168.0.1:40000 -listen 127.0.0.1:8080
package main

import (
	"bytes"
	"encoding/binary"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

var (
	deviceAddr *net.UDPAddr
	udpConn    *net.UDPConn
	seq        atomic.Uint32

	mu       sync.Mutex
	viewers  = map[chan []byte]struct{}{}
	lastJPEG []byte

	devName  atomic.Value // string
	battery  atomic.Uint32
	pkts     atomic.Uint64
	bytesN   atomic.Uint64
	frames   atomic.Uint64
	hbSent   atomic.Uint64
	hbErr    atomic.Value // string
	lastRecv atomic.Int64 // unix ms, 0 = never

	statFPS atomic.Uint64
	statKB  atomic.Uint64
)

func u16(b []byte, o int) int    { return int(binary.LittleEndian.Uint16(b[o:])) }
func u32(b []byte, o int) uint32 { return binary.LittleEndian.Uint32(b[o:]) }

// routeInfo 返回内核为到达设备而选择的本地 IP，以及是否与设备同 /24。
func routeInfo() (string, bool) {
	c, err := net.Dial("udp4", deviceAddr.String())
	if err != nil {
		return "", false
	}
	defer c.Close()
	la, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok || la.IP == nil {
		return "", false
	}
	a, b := la.IP.To4(), deviceAddr.IP.To4()
	same := a != nil && b != nil && a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
	return la.IP.String(), same
}

func publish(f []byte) {
	frames.Add(1)
	mu.Lock()
	lastJPEG = f
	for ch := range viewers {
		select {
		case ch <- f:
		default: // 慢客户端只保留最新帧
		}
	}
	mu.Unlock()
}

func subscribe() chan []byte {
	ch := make(chan []byte, 1)
	mu.Lock()
	viewers[ch] = struct{}{}
	if lastJPEG != nil {
		ch <- lastJPEG
	}
	mu.Unlock()
	return ch
}

func unsubscribe(ch chan []byte) {
	mu.Lock()
	delete(viewers, ch)
	mu.Unlock()
}

func send(cmd byte) {
	s := uint16(seq.Add(1))
	p := []byte{0x63, 0x63, cmd, byte(s), byte(s >> 8), 0, 0}
	_, _ = udpConn.WriteToUDP(p, deviceAddr)
}

func udpLoop() {
	hb := []byte{0x63, 0x63, 0x01, 0, 0, 0, 0}
	buf := make([]byte, 4096)
	var stream []byte
	lastSeq := -1
	var lastHB time.Time

	for {
		if time.Since(lastHB) >= time.Second {
			if _, err := udpConn.WriteToUDP(hb, deviceAddr); err != nil {
				hbErr.Store(err.Error())
			} else {
				hbErr.Store("")
				hbSent.Add(1)
			}
			lastHB = time.Now()
		}
		_ = udpConn.SetReadDeadline(time.Now().Add(300 * time.Millisecond))
		n, _, err := udpConn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		p := buf[:n]
		if len(p) < 7 || p[0] != 0x63 || p[1] != 0x63 {
			continue
		}
		lastRecv.Store(time.Now().UnixMilli())
		pkts.Add(1)
		bytesN.Add(uint64(n))

		switch p[2] {
		case 0x01, 0x0f, 0x10, 0x05, 0x02:
			if len(p) >= 7+99 {
				s := p[7 : 7+99]
				if i := bytes.IndexByte(s, 0); i > 0 {
					devName.Store(string(s[:i]))
				}
			}
		case 0x03:
			if n < 0x36 {
				continue
			}
			ln := u16(p, 0x34)
			if ln > n-0x36 {
				ln = n - 0x36
			}
			battery.Store(u32(p, 0x10))
			sq := u16(p, 3)
			if lastSeq >= 0 && sq != (lastSeq+1)&0xffff {
				stream = stream[:0] // 序号跳变：丢弃半帧
			}
			lastSeq = sq
			stream = append(stream, p[0x36:0x36+ln]...)

			for {
				s := bytes.Index(stream, []byte{0xff, 0xd8})
				if s < 0 {
					if len(stream) > 1 {
						stream = stream[len(stream)-1:]
					}
					break
				}
				if s > 0 {
					stream = stream[s:]
				}
				e := bytes.Index(stream, []byte{0xff, 0xd9})
				if e < 0 {
					if len(stream) > 4<<20 {
						stream = stream[:0]
					}
					break
				}
				frame := append([]byte(nil), stream[:e+2]...)
				publish(frame)
				stream = stream[e+2:]
			}
		}
	}
}

const page = `<!doctype html><meta charset=utf-8><title>Zdeer Earpick</title>
<style>
body{margin:0;background:#111;color:#ddd;font:14px system-ui;display:flex;flex-direction:column;height:100vh}
#wrap{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden}
img{max-width:100%;max-height:100%}
#msg{display:none;padding:8px 12px;background:#5a1d1d;color:#fff}
#bar{display:flex;gap:8px;align-items:center;padding:8px 12px;background:#1b1b1b}
button{background:#2a2a2a;color:#eee;border:1px solid #444;border-radius:6px;padding:6px 12px;cursor:pointer}
button:hover{background:#333}
#st{margin-left:auto;color:#888;font-variant-numeric:tabular-nums}
</style>
<div id=wrap><img id=v src="/stream"></div>
<div id=msg></div>
<div id=bar>
<button onclick="c('led_on')">补光灯开</button>
<button onclick="c('led_off')">补光灯关</button>
<button onclick="c('mirror')">镜像</button>
<button onclick="location.href='/snapshot'">存一张</button>
<div id=st>等待画面…</div>
</div>
<script>
function c(x){fetch('/cmd?c='+x)}
async function tick(){
 try{
  const j=await (await fetch('/diag')).json();
  document.getElementById('st').textContent=
    (j.device||'?')+' | ADC '+j.battery+' | '+j.fps+' fps | '+j.kb+' KB/s';
  const m=document.getElementById('msg');
  if(!j.sameSubnet){
    m.style.display='block'; m.style.background='#5a1d1d';
    m.textContent='本机 '+j.localIP+' 与设备 '+j.deviceIP+' 不在同一网段：请先把电脑连到设备热点（zdeer earpick-*）。';
  } else if(j.lastReplyAgo<0 || j.lastReplyAgo>3000){
    m.style.display='block'; m.style.background='#5a4a1d';
    m.textContent='已进入设备网段，但设备无响应。心跳已发 '+j.hbSent+' 次，最后错误：'+(j.hbErr||'无')+'。请确认设备已开机。';
  } else {
    m.style.display='none';
  }
 }catch(e){}
}
setInterval(tick,1000); tick();
</script>`

func main() {
	dev := flag.String("device", "192.168.0.1:40000", "设备 UDP 地址")
	listen := flag.String("listen", "127.0.0.1:8080", "HTTP 监听地址")
	flag.Parse()

	var err error
	deviceAddr, err = net.ResolveUDPAddr("udp4", *dev)
	if err != nil {
		log.Fatal(err)
	}
	udpConn, err = net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		log.Fatal(err)
	}
	go udpLoop()

	go func() {
		var pFrames, pBytes uint64
		for range time.Tick(time.Second) {
			curFrames, curBytes := frames.Load(), bytesN.Load()
			statFPS.Store(curFrames - pFrames)
			statKB.Store((curBytes - pBytes) / 1024)
			pFrames, pBytes = curFrames, curBytes
		}
	}()

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, page)
	})

	http.HandleFunc("/stream", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary=frame")
		w.Header().Set("Cache-Control", "no-store")
		fl, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "no flush", 500)
			return
		}
		ch := subscribe()
		defer unsubscribe(ch)
		for {
			select {
			case f := <-ch:
				fmt.Fprintf(w, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n", len(f))
				_, _ = w.Write(f)
				_, _ = w.Write([]byte("\r\n"))
				fl.Flush()
			case <-r.Context().Done():
				return
			}
		}
	})

	http.HandleFunc("/snapshot", func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		f := lastJPEG
		mu.Unlock()
		if f == nil {
			http.Error(w, "no frame", 404)
			return
		}
		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Content-Disposition", `attachment; filename="earpick.jpg"`)
		_, _ = w.Write(f)
	})

	http.HandleFunc("/cmd", func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Query().Get("c") {
		case "led_on":
			send(0x0f)
		case "led_off":
			send(0x10)
		case "mirror":
			send(0x05)
		case "stop":
			send(0x02)
		default:
			http.Error(w, "unknown cmd", 400)
			return
		}
		fmt.Fprint(w, "ok")
	})

	http.HandleFunc("/diag", func(w http.ResponseWriter, r *http.Request) {
		localIP, same := routeInfo()
		name, _ := devName.Load().(string)
		herr, _ := hbErr.Load().(string)
		ago := int64(-1)
		if t := lastRecv.Load(); t > 0 {
			ago = time.Now().UnixMilli() - t
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintf(w, `{"device":%q,"deviceIP":%q,"localIP":%q,"sameSubnet":%t,`+
			`"hbSent":%d,"hbErr":%q,"lastReplyAgo":%d,"pkts":%d,"bytes":%d,`+
			`"frames":%d,"fps":%d,"kb":%d,"battery":%d}`,
			name, deviceAddr.IP.String(), localIP, same,
			hbSent.Load(), herr, ago, pkts.Load(), bytesN.Load(),
			frames.Load(), statFPS.Load(), statKB.Load(), battery.Load())
	})

	log.Printf("device %s, open http://%s", deviceAddr, *listen)
	log.Fatal(http.ListenAndServe(*listen, nil))
}
