require('dotenv').config();

const {
    Client, GatewayIntentBits, Events, EmbedBuilder, PermissionFlagsBits, MessageFlags, escapeMarkdown
} = require('discord.js');
const WebSocket = require('ws');
const fs     = require('fs');
const path   = require('path');
const crypto = require('crypto');

// ─── Constants ────────────────────────────────────────────────────────────────

const REGISTER_GUILD = process.env.ADMIN_GUILD_ID || '1482117940064289013';
const REGISTER_USER  = process.env.OWNER_ID       || '1321846194758222017';

const CODE_RE       = /^[A-Z0-9-]{4,32}$/;
const EPHEMERAL     = MessageFlags.Ephemeral;
const NO_MENTIONS   = { parse: [] };
const MAX_MESSAGE   = 1990 - '```ansi\n\n```'.length;
const MAX_BUFFER    = 2000;   // lines waiting per channel before the oldest are dropped
const MAX_LINE      = 30000;  // one log entry (stack traces included)
const LINK_TTL      = 10 * 60 * 1000;                      // a /link code is valid for 10 minutes
const LINK_CHARS    = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  // no 0/O/1/I mix-ups
const MAX_FAILED_CLAIMS = 5;                               // wrong codes per server per 10 minutes

// ─── Persistent storage ───────────────────────────────────────────────────────

const DATA_DIR    = path.join(__dirname, 'data');
const LINKS_FILE  = path.join(DATA_DIR, 'links.json');
const GUILDS_FILE = path.join(DATA_DIR, 'allowed_guilds.json');

function readJson(file, fallback) {
    try {
        fs.mkdirSync(DATA_DIR, { recursive: true });
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch { return fallback; }
}

// Write to a temp file first, so a crash can never leave a half-written file behind
function writeJson(file, data) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(file + '.tmp', JSON.stringify(data, null, 2));
    fs.renameSync(file + '.tmp', file);
}

// channelId → { code, label, guildId }
let links         = readJson(LINKS_FILE,  {});
let allowedGuilds = readJson(GUILDS_FILE, []);

function isAllowedGuild(guildId) {
    return guildId === REGISTER_GUILD || allowedGuilds.includes(guildId);
}

// Links created before guildId was stored get it from the channel cache
function guildOfLink(channelId) {
    const link = links[channelId];
    return link?.guildId || discordClient.channels.cache.get(channelId)?.guildId || null;
}

function channelsForCode(code) {
    return Object.entries(links)
        .filter(([, v]) => v.code === code)
        .map(([id]) => id);
}

// Removes a channel link; tells the plugin only when no channel is left for its code
function unlinkChannel(channelId) {
    const link = links[channelId];
    if (!link) return null;
    delete links[channelId];
    writeJson(LINKS_FILE, links);
    buffers.delete(channelId);
    clearTimeout(timers.get(channelId));
    timers.delete(channelId);
    if (channelsForCode(link.code).length === 0) {
        const conn = connections.get(link.code);
        if (conn) safeSend(conn.ws, { type: 'unlinked' });
    }
    return link;
}

// ─── Active plugin connections ────────────────────────────────────────────────
// code → { ws, code, name, version, players, maxPlayers, tps, ram, uptime, chunks, entities, plugins, playerNames }

const connections = new Map();

// Recent lines per code – last 30, posted when a channel /links
const recentLines = new Map(); // code → string[]

function pushRecentLine(code, line) {
    if (!recentLines.has(code)) recentLines.set(code, []);
    const buf = recentLines.get(code);
    buf.push(line);
    if (buf.length > 30) buf.shift();
}

// ─── Link codes from /link ────────────────────────────────────────────────────
// /link in Discord creates a one-time code; the server owner enters it on the
// Minecraft server (flowcmds link <code>), which proves access to both sides.

const pendingLinks  = new Map(); // code → { channelId, guildId, label, userId, expires }
const failedClaims  = new Map(); // server id → { count, since }

function createLinkCode(channelId, guildId, label, userId) {
    for (const [code, p] of pendingLinks) {
        if (p.channelId === channelId) pendingLinks.delete(code); // one code per channel
    }
    let code;
    do {
        code = '';
        for (let i = 0; i < 8; i++) code += LINK_CHARS[crypto.randomInt(LINK_CHARS.length)];
        code = code.slice(0, 4) + '-' + code.slice(4);
    } while (pendingLinks.has(code));
    pendingLinks.set(code, { channelId, guildId, label, userId, expires: Date.now() + LINK_TTL });
    return code;
}

setInterval(() => {
    const now = Date.now();
    for (const [code, p] of pendingLinks) if (p.expires < now) pendingLinks.delete(code);
}, 60000);

// The plugin sent a code from /link: connect that channel to this Minecraft server
async function claimLinkCode(entry, rawCode) {
    const fails = failedClaims.get(entry.code);
    if (fails && Date.now() - fails.since > LINK_TTL) failedClaims.delete(entry.code);
    if ((failedClaims.get(entry.code)?.count ?? 0) >= MAX_FAILED_CLAIMS) {
        return { ok: false, message: 'Too many wrong codes. Wait 10 minutes and try again.' };
    }

    const norm = String(rawCode || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    const code = norm.slice(0, 4) + '-' + norm.slice(4);
    const pending = pendingLinks.get(code);
    if (!pending || pending.expires < Date.now()) {
        const f = failedClaims.get(entry.code) ?? { count: 0, since: Date.now() };
        f.count++;
        failedClaims.set(entry.code, f);
        return { ok: false, message: 'Unknown or expired code. Run /link in the Discord channel again and use the new code.' };
    }
    pendingLinks.delete(code);
    failedClaims.delete(entry.code);

    const ch = await discordClient.channels.fetch(pending.channelId).catch(() => null);
    if (!ch) return { ok: false, message: 'The Discord channel no longer exists.' };

    const label = pending.label || entry.name;
    const previous = links[pending.channelId];
    if (previous && previous.code !== entry.code) unlinkChannel(pending.channelId);
    links[pending.channelId] = { code: entry.code, label, guildId: pending.guildId };
    writeJson(LINKS_FILE, links);
    safeSend(entry.ws, { type: 'linked', guildName: ch.guild?.name, channelName: ch.name });
    console.log(`[Link] "${entry.name}" linked to #${ch.name} (${ch.guild?.name})`);

    await ch.send({
        embeds: [new EmbedBuilder().setColor(0x2ecc71).setTitle('Server Linked')
            .setDescription(`This channel is now the console of **${escapeMarkdown(label)}**.\n` +
                'Run commands with `/console`, see players/TPS with `/status`.')],
        allowedMentions: NO_MENTIONS
    }).catch(() => {});
    // Show the last 30 console lines so the channel doesn't start empty
    for (const content of packMessages((recentLines.get(entry.code) || []).flatMap(formatEntry))) {
        await ch.send({ content, allowedMentions: NO_MENTIONS }).catch(() => {});
    }
    return { ok: true, message: `Linked to #${ch.name} in ${ch.guild?.name ?? 'Discord'}.` };
}

function safeSend(ws, obj) {
    if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

// Waits until the plugin sends fresh stats (or the timeout passes)
function waitForStats(entry, ms) {
    return new Promise((resolve) => {
        const timer = setTimeout(resolve, ms);
        entry.statsWaiters.push(() => { clearTimeout(timer); resolve(); });
    });
}

// ─── ANSI colouring for Discord ansi code blocks ─────────────────────────────

// One log entry → display lines. Entries can span several lines (stack traces).
function formatEntry(entry) {
    const color = /\[SEVERE\]|\[ERROR\]/.test(entry) ? '31'   // red
                : /\[WARN\]|\[WARNING\]/.test(entry) ? '33'   // yellow
                : null;
    const out = [];
    for (let line of entry.replace(/```/g, '`​``').split(/\r?\n/)) {
        if (color) line = `\x1b[${color}m${line}\x1b[0m`;
        // A single line longer than one message is cut into pieces
        for (let i = 0; i < line.length || i === 0; i += MAX_MESSAGE) out.push(line.slice(i, i + MAX_MESSAGE));
    }
    return out;
}

// Packs display lines into as few code-block messages as possible
function packMessages(lines) {
    const messages = [];
    let body = '';
    for (const line of lines) {
        if (body && body.length + 1 + line.length > MAX_MESSAGE) {
            messages.push(body);
            body = '';
        }
        body += (body ? '\n' : '') + line;
    }
    if (body) messages.push(body);
    return messages.map(m => '```ansi\n' + m + '\n```');
}

// ─── Per-channel output batching ──────────────────────────────────────────────

const buffers  = new Map(); // channelId → { lines: string[], dropped: number }
const timers   = new Map();
const flushing = new Set();  // channels with a send in progress – keeps the line order

function queueLine(channelId, entry) {
    if (!buffers.has(channelId)) buffers.set(channelId, { lines: [], dropped: 0 });
    const buf = buffers.get(channelId);
    buf.lines.push(...formatEntry(entry));

    if (buf.lines.length > MAX_BUFFER) {
        buf.dropped += buf.lines.length - MAX_BUFFER;
        buf.lines.splice(0, buf.lines.length - MAX_BUFFER);
    }
    if (!timers.has(channelId)) {
        timers.set(channelId, setTimeout(() => flushChannel(channelId), 1500));
    }
}

async function flushChannel(channelId) {
    timers.delete(channelId);
    if (flushing.has(channelId)) return; // the running flush schedules the next one
    const buf = buffers.get(channelId);
    if (!buf || (!buf.lines.length && !buf.dropped)) return;

    if (buf.dropped) {
        buf.lines.unshift(`\x1b[33m... ${buf.dropped} lines skipped (the console was faster than Discord allows)\x1b[0m`);
        buf.dropped = 0;
    }

    flushing.add(channelId);
    try {
        const ch = await discordClient.channels.fetch(channelId);
        // Up to 3 messages per round; discord.js waits by itself when Discord rate-limits us
        for (let sent = 0; sent < 3 && buf.lines.length; sent++) {
            let used = 0, size = 0;
            while (used < buf.lines.length && (used === 0 || size + 1 + buf.lines[used].length <= MAX_MESSAGE)) {
                size += (used ? 1 : 0) + buf.lines[used].length;
                used++;
            }
            const [content] = packMessages(buf.lines.splice(0, used));
            await ch.send({ content, allowedMentions: NO_MENTIONS });
        }
    } catch (e) {
        // 10003 Unknown Channel, 50001 Missing Access: the channel is gone for us
        if (e.code === 10003 || e.code === 50001) {
            console.warn(`[output] channel ${channelId} is gone – unlinking it`);
            unlinkChannel(channelId);
        } else {
            if (e.code === 50013) buf.lines.length = 0; // Missing Permissions: don't pile up forever
            console.error(`[output] channel ${channelId}:`, e.message);
        }
    } finally {
        flushing.delete(channelId);
        const rest = buffers.get(channelId);
        if (rest && (rest.lines.length || rest.dropped) && !timers.has(channelId)) {
            timers.set(channelId, setTimeout(() => flushChannel(channelId), 1500));
        }
    }
}

// ─── WebSocket server – Minecraft plugins connect here ────────────────────────

const BOT_WS_PORT = parseInt(process.env.BOT_WS_PORT || '8005', 10);
const wss = new WebSocket.Server({ port: BOT_WS_PORT, maxPayload: 1024 * 1024 });

wss.on('listening', () =>
    console.log(`[WS] Listening on port ${BOT_WS_PORT}`)
);

// Heartbeat: servers that crashed without closing the connection are removed after ~60s
setInterval(() => {
    for (const ws of wss.clients) {
        if (ws.isAlive === false) { ws.terminate(); continue; }
        ws.isAlive = false;
        ws.ping();
    }
}, 30000);

wss.on('connection', (ws) => {
    let entry = null;
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    const helloTimer = setTimeout(() => { if (!entry) ws.close(4001, 'No hello received'); }, 15000);

    ws.on('message', (raw, isBinary) => {
        if (isBinary) return;
        let pkt;
        try { pkt = JSON.parse(raw.toString()); } catch { return; }
        if (!pkt || typeof pkt !== 'object') return;

        // ── hello ──────────────────────────────────────────────────────────────
        // Also sent again on the same connection when the plugin resets its code
        if (pkt.type === 'hello') {
            const code = String(pkt.code || '').toUpperCase().trim();
            if (!CODE_RE.test(code)) { ws.close(4003, 'Invalid link code'); return; }
            clearTimeout(helloTimer);

            if (entry && entry.code !== code && connections.get(entry.code) === entry) {
                connections.delete(entry.code);
            }

            // Same code connected twice (e.g. copied server folder): the new connection wins
            const old = connections.get(code);
            if (old && old.ws !== ws) old.ws.close(4004, 'Replaced by a new connection');

            entry = {
                ws,
                code,
                name:         String(pkt.name    || 'Unknown').slice(0, 64),
                version:      String(pkt.version || '?').slice(0, 100),
                players:      Number(pkt.players)    || 0,
                maxPlayers:   Number(pkt.maxPlayers) || 0,
                playerNames:  [],
                tps:          'N/A',
                ram:          'N/A',
                uptime:       'N/A',
                chunks:       0,
                entities:     0,
                plugins:      0,
                statsWaiters: [],
            };
            connections.set(code, entry);
            console.log(`[WS] "${entry.name}" connected (${code})`);

            const linked = channelsForCode(code).length > 0;
            safeSend(ws, { type: linked ? 'linked' : 'unlinked' });

            for (const ch of channelsForCode(code)) {
                queueLine(ch, `[FlowConsoleCmds] "${entry.name}" connected (${entry.version})`);
            }
            return;
        }

        if (!entry) return;

        // ── claim: code from /link entered on the Minecraft server ─────────────
        if (pkt.type === 'claim') {
            const id = String(pkt.id || '');
            claimLinkCode(entry, pkt.code)
                .catch((e) => { console.error('[Link] claim failed:', e); return { ok: false, message: 'Internal error, try again.' }; })
                .then((r) => safeSend(ws, { type: 'result', id, ...r }));
            return;
        }

        // ── unlink-request: flowcmds unlink on the Minecraft server ────────────
        if (pkt.type === 'unlink-request') {
            const id = String(pkt.id || '');
            const channels = channelsForCode(entry.code);
            safeSend(ws, { type: 'result', id, ok: true,
                message: channels.length ? `Unlinked from ${channels.length} Discord channel(s).` : 'This server was not linked.' });
            for (const channelId of channels) {
                unlinkChannel(channelId); // the last one tells the plugin "unlinked"
                discordClient.channels.fetch(channelId)
                    .then((c) => c.send({ content: `🔌 **${escapeMarkdown(entry.name)}** was unlinked on the Minecraft server (\`flowcmds unlink\`).`, allowedMentions: NO_MENTIONS }))
                    .catch(() => {});
            }
            if (!channels.length) safeSend(ws, { type: 'unlinked' });
            return;
        }

        // ── log ────────────────────────────────────────────────────────────────
        if (pkt.type === 'log') {
            const line = String(pkt.data || '').trim().slice(0, MAX_LINE);
            if (!line) return;
            pushRecentLine(entry.code, line);
            for (const ch of channelsForCode(entry.code)) queueLine(ch, line);
            return;
        }

        // ── stats ──────────────────────────────────────────────────────────────
        if (pkt.type === 'stats') {
            if (pkt.players     !== undefined) entry.players     = Number(pkt.players) || 0;
            if (pkt.maxPlayers  !== undefined) entry.maxPlayers  = Number(pkt.maxPlayers) || 0;
            if (Array.isArray(pkt.playerNames)) entry.playerNames = pkt.playerNames.slice(0, 100).map(String);
            if (pkt.tps         !== undefined) entry.tps         = String(pkt.tps).slice(0, 50);
            if (pkt.ram         !== undefined) entry.ram         = String(pkt.ram).slice(0, 50);
            if (pkt.uptime      !== undefined) entry.uptime      = String(pkt.uptime).slice(0, 50);
            if (pkt.chunks      !== undefined) entry.chunks      = Number(pkt.chunks) || 0;
            if (pkt.entities    !== undefined) entry.entities    = Number(pkt.entities) || 0;
            if (pkt.plugins     !== undefined) entry.plugins     = Number(pkt.plugins) || 0;
            for (const resolve of entry.statsWaiters.splice(0)) resolve();
            return;
        }

        // ── pong ───────────────────────────────────────────────────────────────
        if (pkt.type === 'pong') {
            if (pkt.players    !== undefined) entry.players    = Number(pkt.players) || 0;
            if (pkt.maxPlayers !== undefined) entry.maxPlayers = Number(pkt.maxPlayers) || 0;
        }
    });

    ws.on('close', () => {
        clearTimeout(helloTimer);
        if (!entry) return;
        for (const resolve of entry.statsWaiters.splice(0)) resolve();
        // Only if this is still the active connection – a replaced one must not remove its successor
        if (connections.get(entry.code) !== entry) return;
        connections.delete(entry.code);
        console.log(`[WS] "${entry.name}" disconnected`);
        for (const ch of channelsForCode(entry.code)) {
            queueLine(ch, `[FlowConsoleCmds] "${entry.name}" disconnected`);
        }
    });

    ws.on('error', (err) => console.error('[WS] Error:', err.message));
});

// ─── Discord bot ──────────────────────────────────────────────────────────────

const discordClient = new Client({
    intents: [GatewayIntentBits.Guilds]
});

discordClient.once(Events.ClientReady, async (c) => {
    console.log(`[Discord] Logged in as ${c.user.tag}`);

    // Store the guild of links created by older versions; drop links to deleted channels
    let changed = false;
    for (const [channelId, link] of Object.entries(links)) {
        if (link.guildId) continue;
        const ch = await discordClient.channels.fetch(channelId).catch(e => e);
        if (ch && !(ch instanceof Error)) {
            link.guildId = ch.guildId;
            changed = true;
        } else if (ch?.code === 10003 || ch?.code === 50001) {
            delete links[channelId];
            changed = true;
        }
    }
    if (changed) writeJson(LINKS_FILE, links);
});

discordClient.on('error', (err) => console.error('[Discord] Client error:', err.message));
process.on('unhandledRejection', (err) => console.error('[Process] Unhandled rejection:', err));

// Auto-unlink deleted channels
discordClient.on(Events.ChannelDelete, (channel) => {
    const link = unlinkChannel(channel.id);
    if (link) console.log(`[Discord] Channel ${channel.id} deleted – auto-unlinked from "${link.label}"`);
});

// Bot removed from a Discord server: unlink all its channels
discordClient.on(Events.GuildDelete, (guild) => {
    for (const channelId of Object.keys(links)) {
        if (guildOfLink(channelId) === guild.id) unlinkChannel(channelId);
    }
});

discordClient.on(Events.InteractionCreate, async (interaction) => {
    if (!interaction.isChatInputCommand()) return;
    try {
        await handleCommand(interaction);
    } catch (err) {
        // Without this, one failed reply (e.g. Discord timeout) would crash the whole bot
        console.error(`[Discord] /${interaction.commandName} failed:`, err);
        const msg = { content: 'Something went wrong.', flags: EPHEMERAL };
        const send = interaction.deferred || interaction.replied ? interaction.followUp(msg) : interaction.reply(msg);
        await send.catch(() => {});
    }
});

async function handleCommand(interaction) {
    const { commandName, channelId, guildId } = interaction;

    if (!interaction.inGuild()) {
        return interaction.reply({ content: 'Use this command in a Discord server.', flags: EPHEMERAL });
    }

    // ── /register ─────────────────────────────────────────────────────────────
    if (commandName === 'register') {
        if (guildId !== REGISTER_GUILD || interaction.user.id !== REGISTER_USER) {
            return interaction.reply({ content: 'You are not allowed to use this command.', flags: EPHEMERAL });
        }
        const targetId = interaction.options.getString('guild_id').trim();
        if (!/^\d{17,20}$/.test(targetId)) {
            return interaction.reply({ content: 'That is not a valid server ID.', flags: EPHEMERAL });
        }
        if (!allowedGuilds.includes(targetId)) {
            allowedGuilds.push(targetId);
            writeJson(GUILDS_FILE, allowedGuilds);
        }
        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x2ecc71).setTitle('Guild Registered')
                .setDescription(`\`${targetId}\` can now use FlowConsoleCmds.`)]
        });
    }

    // ── /unregister ───────────────────────────────────────────────────────────
    if (commandName === 'unregister') {
        if (guildId !== REGISTER_GUILD || interaction.user.id !== REGISTER_USER) {
            return interaction.reply({ content: 'You are not allowed to use this command.', flags: EPHEMERAL });
        }
        const targetId = interaction.options.getString('guild_id').trim();
        allowedGuilds = allowedGuilds.filter(id => id !== targetId);
        writeJson(GUILDS_FILE, allowedGuilds);
        let removed = 0;
        for (const id of Object.keys(links)) {
            if (guildOfLink(id) === targetId) { unlinkChannel(id); removed++; }
        }
        return interaction.reply({ content: `Guild \`${targetId}\` has been unregistered (${removed} channel(s) unlinked).` });
    }

    // ── /registered ───────────────────────────────────────────────────────────
    if (commandName === 'registered') {
        if (guildId !== REGISTER_GUILD || interaction.user.id !== REGISTER_USER) {
            return interaction.reply({ content: 'You are not allowed to use this command.', flags: EPHEMERAL });
        }
        const list = allowedGuilds.length
            ? allowedGuilds.map(id => `\`${id}\``).join('\n')
            : 'No guilds registered yet.';
        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x5865f2).setTitle('Registered Guilds').setDescription(list.slice(0, 4000))]
        });
    }

    // ── Admin permission check for all remaining commands ─────────────────────
    if (!interaction.memberPermissions?.has(PermissionFlagsBits.Administrator)) {
        return interaction.reply({ content: 'You need the Administrator permission to use this command.', flags: EPHEMERAL });
    }

    // ── /help ─────────────────────────────────────────────────────────────────
    if (commandName === 'help') {
        return interaction.reply({
            embeds: [new EmbedBuilder()
                .setColor(0x5865f2)
                .setTitle('FlowDiscordConsoleCmds – Setup Guide')
                .setDescription('Bridge your Minecraft server console directly into Discord.')
                .addFields(
                    { name: '1.  Install the plugin',
                      value: 'Drop `FlowDiscordConsoleCmds.jar` (received after purchase) into your server\'s `plugins/` folder and restart the server.' },
                    { name: '2.  Get a link code',
                      value: 'Run `/link` in the Discord channel that should become the console.\nYou get a one-time code, valid for 10 minutes.' },
                    { name: '3.  Link on the server',
                      value: 'Enter this in your server console:\n```\nflowcmds link XXXX-XXXX\n```\nOr in-game as OP: `/flowcmds link XXXX-XXXX`' },
                    { name: 'Discord commands',
                      value: '`/link` — link a channel\n`/unlink` — unlink a channel\n`/console <cmd>` — run a console command\n`/status` — show server stats\n`/servers` — list connected servers' },
                    { name: 'In-game commands',
                      value: '`/flowcmds link <code>` — link this server\n`/flowcmds unlink` — unlink\n`/flowcmds status` — connection status\n`/flowcmds reconnect` — force reconnect\n`/flowcmds reload` — reload config' },
                    { name: 'Requirements',
                      value: '- This Discord server must be registered by the bot owner\n- Discord **Administrator** permission to use all commands' }
                )
                .setFooter({ text: 'FlowDiscordConsoleCmds • Bukkit/Spigot/Paper' })
            ],
            flags: EPHEMERAL
        });
    }

    // ── Only registered Discord servers may use the console commands ──────────
    if (!isAllowedGuild(guildId)) {
        return interaction.reply({
            content: 'This Discord server is not registered for FlowConsoleCmds. Ask the bot owner to `/register` it.',
            flags: EPHEMERAL
        });
    }

    // ── /link [label] ─────────────────────────────────────────────────────────
    // Gives a one-time code; entering it on the Minecraft server links this channel
    if (commandName === 'link') {
        const label = (interaction.options.getString('label') || '').trim().slice(0, 64) || null;

        if (!interaction.channel?.isTextBased()) {
            return interaction.reply({ content: 'Use this command in a text channel.', flags: EPHEMERAL });
        }
        if (!interaction.appPermissions?.has([PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages])) {
            return interaction.reply({ content: 'I need **View Channel** and **Send Messages** permission in this channel.', flags: EPHEMERAL });
        }
        const existing = links[channelId];
        if (existing) {
            return interaction.reply({
                content: `This channel is already the console of **${escapeMarkdown(existing.label)}**. Use \`/unlink\` first.`,
                flags: EPHEMERAL
            });
        }

        const code = createLinkCode(channelId, guildId, label, interaction.user.id);
        return interaction.reply({
            embeds: [new EmbedBuilder()
                .setColor(0x5865f2)
                .setTitle('Link a Minecraft server')
                .setDescription(
                    `Your link code: **\`${code}\`**\n` +
                    'Valid for 10 minutes – only you can see it.\n\n' +
                    '**Enter it on your Minecraft server:**\n' +
                    `Server console: \`flowcmds link ${code}\`\n` +
                    `In game (OP): \`/flowcmds link ${code}\``)
                .setFooter({ text: 'The server must have the FlowDiscordConsoleCmds plugin and be connected to the bot.' })
            ],
            flags: EPHEMERAL
        });
    }

    // ── /unlink ───────────────────────────────────────────────────────────────
    if (commandName === 'unlink') {
        const link = unlinkChannel(channelId);
        if (!link) return interaction.reply({ content: 'This channel is not linked to any server.', flags: EPHEMERAL });
        return interaction.reply({ content: `Unlinked from **${escapeMarkdown(link.label)}**.`, allowedMentions: NO_MENTIONS });
    }

    // ── /status ───────────────────────────────────────────────────────────────
    if (commandName === 'status') {
        const link = links[channelId];
        if (!link) return interaction.reply({ content: 'This channel is not linked.\nUse `/link <code>`.', flags: EPHEMERAL });

        const conn = connections.get(link.code);
        if (conn) {
            // Ask the plugin for fresh numbers and wait a moment for them
            await interaction.deferReply();
            safeSend(conn.ws, { type: 'ping' });
            await waitForStats(conn, 2000);
        }

        const embed = new EmbedBuilder()
            .setColor(conn ? 0x2ecc71 : 0xe74c3c)
            .setTitle(link.label + ' – Status')
            .addFields(
                { name: 'Status',  value: conn ? 'Online'     : 'Offline', inline: true },
                { name: 'Version', value: conn ? conn.version : 'N/A',     inline: true }
            );

        if (conn) {
            const playerList = conn.playerNames.length ? conn.playerNames.join(', ') : 'None';
            embed.addFields(
                { name: 'Players',  value: `${conn.players}/${conn.maxPlayers} – ${playerList}`.slice(0, 1024), inline: false },
                { name: 'TPS',      value: conn.tps,      inline: true },
                { name: 'RAM',      value: conn.ram,      inline: true },
                { name: 'Uptime',   value: conn.uptime,   inline: true },
                { name: 'Chunks',   value: String(conn.chunks),   inline: true },
                { name: 'Entities', value: String(conn.entities), inline: true },
                { name: 'Plugins',  value: String(conn.plugins),  inline: true }
            );
            return interaction.editReply({ embeds: [embed] });
        }

        return interaction.reply({ embeds: [embed] });
    }

    // ── /servers ──────────────────────────────────────────────────────────────
    // Only the servers linked in THIS Discord server, and never their link codes
    if (commandName === 'servers') {
        const mine = Object.entries(links).filter(([id]) => guildOfLink(id) === guildId);
        if (mine.length === 0) {
            return interaction.reply({ content: 'No Minecraft servers are linked in this Discord server.', flags: EPHEMERAL });
        }
        const lines = mine.map(([id, link]) => {
            const c = connections.get(link.code);
            return c
                ? `🟢 **${escapeMarkdown(link.label)}** → <#${id}> – ${c.players}/${c.maxPlayers} players | ${escapeMarkdown(c.version)}`
                : `🔴 **${escapeMarkdown(link.label)}** → <#${id}> – offline`;
        }).join('\n');
        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x5865f2).setTitle('Linked Servers').setDescription(lines.slice(0, 4000))]
        });
    }

    // ── /console <command> ────────────────────────────────────────────────────
    if (commandName === 'console') {
        const link = links[channelId];
        if (!link) return interaction.reply({ content: 'This channel is not linked.\nUse `/link <code>` first.', flags: EPHEMERAL });

        const conn = connections.get(link.code);
        if (!conn || conn.ws.readyState !== WebSocket.OPEN) {
            return interaction.reply({ content: 'The Minecraft server is not connected right now.', flags: EPHEMERAL });
        }

        const command = interaction.options.getString('command').trim();
        if (/[\r\n]/.test(command)) {
            return interaction.reply({ content: 'Commands must be a single line.', flags: EPHEMERAL });
        }
        safeSend(conn.ws, { type: 'exec', data: command });

        return interaction.reply({
            embeds: [new EmbedBuilder()
                .setColor(0x2ecc71)
                .setDescription('`> ' + command.replace(/`/g, 'ˋ').slice(0, 1000) + '`')
                .setFooter({ text: `Sent by ${interaction.user.tag} → ${link.label}` })
                .setTimestamp()
            ]
        });
    }
}

discordClient.login(process.env.DISCORD_TOKEN);
