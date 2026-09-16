import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'

const table = new Uint32Array(256)
for (let n = 0; n < 256; n += 1) {
  let c = n
  for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  table[n] = c >>> 0
}

const crc32 = (buffer) => {
  let c = 0xffffffff
  for (const byte of buffer) c = table[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

const chunk = (type, data) => {
  const name = Buffer.from(type)
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(Buffer.concat([name, data])))
  return Buffer.concat([length, name, data, crc])
}

const drawIcon = (size) => {
  const pixels = Buffer.alloc(size * size * 4)
  const set = (x, y, [r, g, b, a = 255]) => {
    if (x < 0 || x >= size || y < 0 || y >= size) return
    const i = (y * size + x) * 4
    pixels[i] = r; pixels[i + 1] = g; pixels[i + 2] = b; pixels[i + 3] = a
  }
  const bg = [16, 18, 15, 255]
  const brass = [212, 181, 111, 255]
  const lime = [239, 255, 157, 255]
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) set(x, y, bg)
  }
  const cx = size / 2
  const cy = size * 0.62
  const radius = size * 0.29
  const stroke = Math.max(4, Math.round(size * 0.045))
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const dx = x - cx
      const dy = y - cy
      const distance = Math.hypot(dx, dy)
      if (dy <= 0 && Math.abs(distance - radius) <= stroke / 2) set(x, y, brass)
      if (Math.abs(dx) <= stroke / 2 && y >= size * 0.2 && y <= cy) set(x, y, lime)
      if (distance <= size * 0.065) set(x, y, lime)
    }
  }
  const row = size * 4 + 1
  const raw = Buffer.alloc(row * size)
  for (let y = 0; y < size; y += 1) {
    raw[y * row] = 0
    pixels.copy(raw, y * row + 1, y * size * 4, (y + 1) * size * 4)
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header[8] = 8
  header[9] = 6
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

for (const size of [192, 512]) {
  const output = resolve(`public/icons/icon-${size}.png`)
  mkdirSync(dirname(output), { recursive: true })
  writeFileSync(output, drawIcon(size))
}
