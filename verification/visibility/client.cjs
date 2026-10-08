const protocol = require('minecraft-protocol');
const data = require('minecraft-data')('26.2');
const diamond = data.blocksByName.diamond_block.minStateId;
const originChunk = Number(process.env.VISIBILITY_CHUNK || 128);
const acknowledged = new Set();
const client = protocol.createClient({ host: '127.0.0.1', port: 25708, username: 'VisibilityProbe', auth: 'offline', version: '26.2' });

function sectionIsDiamond(buffer, requestedSection) {
    let offset = 0;
    function varint() {
        let value = 0;
        for (let shift = 0; shift < 35; shift += 7) {
            if (offset >= buffer.length) throw new Error('Truncated palette');
            const byte = buffer[offset++];
            value |= (byte & 127) << shift;
            if (!(byte & 128)) return value;
        }
        throw new Error('Invalid palette varint');
    }
    function container(entries, paletteLimit, inspect) {
        const bits = buffer[offset++];
        if (bits === 0) return varint() === diamond;
        if (bits > 32) throw new Error(`Invalid palette bits: ${bits}`);
        let palette;
        if (bits <= paletteLimit) {
            const count = varint();
            palette = Array.from({ length: count }, varint);
        }
        const perLong = Math.floor(64 / bits);
        const longs = Math.ceil(entries / perLong);
        const start = offset;
        offset += longs * 8;
        if (offset > buffer.length) throw new Error('Truncated section storage');
        if (!inspect) return false;
        const mask = (1n << BigInt(bits)) - 1n;
        for (let index = 0; index < entries; index++) {
            const packed = buffer.readBigUInt64BE(start + Math.floor(index / perLong) * 8);
            const value = Number((packed >> BigInt((index % perLong) * bits)) & mask);
            if ((palette ? palette[value] : value) !== diamond) return false;
        }
        return true;
    }
    for (let section = 0; offset < buffer.length; section++) {
        offset += 4; // Minecraft 26.2 sends both non-air and fluid counts.
        const matches = container(4096, 8, section === requestedSection);
        container(64, 3, false);
        if (section === requestedSection) return matches;
    }
    return false;
}

client.on('login', () => client.write('player_loaded', {}));
client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 64 }));
client.on('position', packet => {
    client.write('teleport_confirm', { teleportId: packet.teleportId });
    client.write('position', { x: packet.x, y: packet.y, z: packet.z, flags: { onGround: true, hasHorizontalCollision: false } });
    client.write('player_loaded', {});
});
client.on('map_chunk', packet => {
    if (packet.z !== originChunk || (packet.x !== originChunk && packet.x !== originChunk + 1)) return;
    if (acknowledged.has(packet.x) || !sectionIsDiamond(packet.chunkData, 12)) return;
    acknowledged.add(packet.x);
    const phase = packet.x === originChunk ? 'first' : 'second';
    client.write('custom_payload', { channel: 'fawe:visibility', data: Buffer.from(phase) });
    process.stdout.write(`CLIENT_VISIBLE ${phase}\n`);
});
client.on('error', error => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
const ticks = setInterval(() => {
    if (client.state === 'play') client.write('tick_end', {});
}, 50);
const timeout = setTimeout(() => { process.exitCode = 1; client.end(); }, 60000);
client.on('end', () => {
    clearInterval(ticks);
    clearTimeout(timeout);
    if (acknowledged.size !== 2) process.exitCode = 1;
});
