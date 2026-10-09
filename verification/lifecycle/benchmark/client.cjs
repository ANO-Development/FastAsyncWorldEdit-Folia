const protocol = require('minecraft-protocol');
const client = protocol.createClient({ host: '127.0.0.1', port: 25708, username: 'VisibilityProbe', auth: 'offline', version: '26.2' });
let phase = 'initial';
let started = Date.now();
let packets = 0;
let first = null;
let last = null;
const chunks = new Set();
function summary() {
    process.stdout.write(JSON.stringify({ phase, packets, uniqueChunks: chunks.size, firstMs: first, lastMs: last, elapsedMs: Date.now() - started }) + '\n');
}
client.on('custom_payload', packet => {
    if (packet.channel !== 'fawe:benchmark') return;
    const message = packet.data.toString();
    if (message.startsWith('start ')) {
        phase = message.slice(6);
        started = Date.now();
        packets = 0;
        first = null;
        last = null;
        chunks.clear();
    } else if (message.startsWith('end ')) {
        summary();
        phase = 'verification';
    }
});
client.on('login', () => {
    client.write('custom_payload', { channel: 'minecraft:register', data: Buffer.from('fawe:benchmark') });
    client.write('player_loaded', {});
});
client.on('chunk_batch_finished', () => client.write('chunk_batch_received', { chunksPerTick: 64 }));
client.on('position', packet => {
    client.write('teleport_confirm', { teleportId: packet.teleportId });
    client.write('position', { x: packet.x, y: packet.y, z: packet.z, flags: { onGround: true, hasHorizontalCollision: false } });
    client.write('player_loaded', {});
});
client.on('map_chunk', packet => {
    if (packet.x < (70001 >> 4) || packet.x > (70245 >> 4) || packet.z < (-70003 >> 4) || packet.z > (-69789 >> 4)) return;
    packets++;
    chunks.add(`${packet.x},${packet.z}`);
    last = Date.now() - started;
    first ??= last;
});
client.on('error', error => { process.stderr.write(`${error.stack}\n`); process.exitCode = 1; });
const ticks = setInterval(() => { if (client.state === 'play') client.write('tick_end', {}); }, 50);
const timeout = setTimeout(() => { process.exitCode = 1; client.end(); }, 1200000);
client.on('end', () => { clearInterval(ticks); clearTimeout(timeout); summary(); });
