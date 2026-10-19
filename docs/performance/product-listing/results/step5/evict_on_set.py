"""측정 전용 Redis에서 목록 캐시가 저장되는 즉시 지워 매 요청을 miss로 만든다."""
import socket
import sys

HOST, PORT = '127.0.0.1', int(sys.argv[1])
PREFIX = b'product:list:v1:'


def command(*parts):
    out = b'*%d\r\n' % len(parts)
    for part in parts:
        part = part if isinstance(part, bytes) else part.encode()
        out += b'$%d\r\n%s\r\n' % (len(part), part)
    return out


class Reader:
    def __init__(self, sock):
        self.file = sock.makefile('rb')

    def read(self):
        line = self.file.readline()
        kind, rest = line[:1], line[1:-2]
        if kind in (b'+', b'-', b':'):
            return rest
        if kind == b'$':
            size = int(rest)
            if size < 0:
                return None
            data = self.file.read(size + 2)
            return data[:-2]
        if kind == b'*':
            return [self.read() for _ in range(int(rest))]
        raise ValueError(line)


sub = socket.create_connection((HOST, PORT))
cmd = socket.create_connection((HOST, PORT))
cmd_reader = Reader(cmd)
cmd.sendall(command('CONFIG', 'SET', 'notify-keyspace-events', 'E$'))
cmd_reader.read()
sub.sendall(command('SUBSCRIBE', '__keyevent@0__:set'))
reader = Reader(sub)
reader.read()
print('ready', flush=True)
while True:
    message = reader.read()
    key = message[2]
    if key.startswith(PREFIX):
        cmd.sendall(command('DEL', key))
        cmd_reader.read()
