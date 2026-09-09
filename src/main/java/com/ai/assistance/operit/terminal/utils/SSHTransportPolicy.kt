package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract

/** 宿主提供路由，终端不持有代理配置或启动代理核心。每次连接重新解析，失败直接传播。 */
object SSHTransportPolicy {
    data class Endpoint(val host: String, val port: Int)

    @Volatile
    var resolveProxy: suspend (String, Int) -> Endpoint? = { _, _ -> null }

    suspend fun openSshProxyOption(host: String, port: Int): String {
        return proxyOption(resolveProxy(host, port))
    }

    fun proxyOption(endpoint: Endpoint?): String {
        if (endpoint == null) return "-o ProxyCommand=none"
        require(endpoint.port in 1..65535)
        // Ubuntu rootfs 自带 Python；SOCKS5 在代理端解析目标域名，避免本地 DNS 改变规则。
        val python = """
import os, select, socket, struct, sys
s = socket.create_connection((sys.argv[1], int(sys.argv[2])), timeout=20)
def exact(n):
    data = b''
    while len(data) < n:
        part = s.recv(n - len(data))
        if not part: raise OSError('SSH proxy closed during handshake')
        data += part
    return data
s.sendall(b'\x05\x01\x00')
if exact(2) != b'\x05\x00': raise OSError('SSH proxy authentication rejected')
host = sys.argv[3].encode('idna')
if not 0 < len(host) < 256: raise ValueError('Invalid SSH hostname')
s.sendall(b'\x05\x01\x00\x03' + bytes([len(host)]) + host + struct.pack('!H', int(sys.argv[4])))
reply = exact(4)
if reply[:3] != b'\x05\x00\x00': raise OSError('SSH proxy CONNECT rejected')
size = {1: 4, 4: 16}.get(reply[3])
if reply[3] == 3: size = exact(1)[0]
if size is None: raise OSError('Invalid SSH proxy response')
exact(size + 2)
s.settimeout(None)
inputs = [s, 0]
while True:
    ready, _, _ = select.select(inputs, [], [])
    if s in ready:
        data = s.recv(65536)
        if not data: break
        sys.stdout.buffer.write(data)
        sys.stdout.buffer.flush()
    if 0 in ready:
        data = os.read(0, 65536)
        if data: s.sendall(data)
        else:
            s.shutdown(socket.SHUT_WR)
            inputs.remove(0)
""".trimIndent()
        val command = "/usr/bin/python3 -c ${quote(python)} ${quote(endpoint.host)} ${endpoint.port} %h %p"
        return "-o ${quote("ProxyCommand=$command") }"
    }

    private fun quote(value: String) = TerminalEnvironmentContract.shellQuote(value)
}
