require('dotenv').config();

const { Client, GatewayIntentBits, Events, EmbedBuilder, PermissionFlagsBits } = require('discord.js');
const WebSocket = require('ws');
const fs   = require('fs');
const path = require('path');

// ─── Constants ────────────────────────────────────────────────────────────────

const REGISTER_GUILD = '1482117940064289013';
const REGISTER_USER  = '1321846194758222017';

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

function writeJson(file, data) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    fs.writeFileSync(file, JSON.stringify(data, null, 2));
}

let links         = readJson(LINKS_FILE,  {});
let allowedGuilds = readJson(GUILDS_FILE, []);

// ─── Active plugin connections ────────────────────────────────────────────────
// code → { ws, name, version, players, maxPlayers, tps, ram, uptime, chunks, entities, plugins, playerNames }

const connections = new Map();

// Recent lines per code – last 30, posted when a channel /links
const recentLines = new Map(); // code → string[]

function pushRecentLine(code, line) {
    if (!recentLines.has(code)) recentLines.set(code, []);
    const buf = recentLines.get(code);
    buf.push(line);
    if (buf.length > 30) buf.shift();
}

// ─── ANSI colouring for Discord ansi code blocks ─────────────────────────────

function colorLine(line) {
    if (/\[SEVERE\]|\[ERROR\]/.test(line))            return `\x1b[31m${line}\x1b[0m`; // red
    if (/\[WARN\]|\[WARNING\]/.test(line))            return `\x1b[33m${line}\x1b[0m`; // yellow
    return line;
}

// ─── Per-channel output batching ──────────────────────────────────────────────

const buffers = new Map();
const timers  = new Map();

function queueLine(channelId, line) {
    if (!buffers.has(channelId)) buffers.set(channelId, []);
    buffers.get(channelId).push(colorLine(line));

    if (buffers.get(channelId).length >= 20) {
        flushChannel(channelId);
    } else if (!timers.has(channelId)) {
        timers.set(channelId, setTimeout(() => flushChannel(channelId), 1500));
    }
}

async function flushChannel(channelId) {
    clearTimeout(timers.get(channelId));
    timers.delete(channelId);

    const buf = buffers.get(channelId);
    if (!buf || !buf.length) return;

    const text = buf.splice(0, 20).join('\n');

    try {
        const ch = await discordClient.channels.fetch(channelId).catch(() => null);
        if (!ch) return;
        for (let i = 0; i < text.length; i += 1990) {
            await ch.send('```ansi\n' + text.slice(i, i + 1990) + '\n```');
        }
    } catch (e) {
        console.error(`[output] channel ${channelId}:`, e.message);
    }

    if (buf.length > 0) {
        timers.set(channelId, setTimeout(() => flushChannel(channelId), 1500));
    }
}

function channelsForCode(code) {
    return Object.entries(links)
        .filter(([, v]) => v.code === code)
        .map(([id]) => id);
}

// ─── WebSocket server – Minecraft plugins connect here ────────────────────────

const BOT_WS_PORT = parseInt(process.env.BOT_WS_PORT || '8080', 10);
const wss = new WebSocket.Server({ port: BOT_WS_PORT });

wss.on('listening', () =>
    console.log(`[WS] Listening on port ${BOT_WS_PORT}`)
);

wss.on('connection', (ws) => {
    let entry = null;

    ws.on('message', (raw) => {
        let pkt;
        try { pkt = JSON.parse(raw.toString()); } catch { return; }

        // ── hello ──────────────────────────────────────────────────────────────
        if (pkt.type === 'hello') {
            const code = (pkt.code || '').toUpperCase().trim();
            if (!code) { ws.close(); return; }

            entry = {
                ws,
                code,
                name:        pkt.name        || 'Unknown',
                version:     pkt.version     || '?',
                players:     pkt.players     ?? 0,
                maxPlayers:  pkt.maxPlayers  ?? 0,
                playerNames: [],
                tps:         'N/A',
                ram:         'N/A',
                uptime:      'N/A',
                chunks:      0,
                entities:    0,
                plugins:     0,
            };
            connections.set(code, entry);
            console.log(`[WS] "${entry.name}" connected (${code})`);

            const linked = channelsForCode(code).length > 0;
            ws.send(JSON.stringify({ type: linked ? 'linked' : 'unlinked' }));

            for (const ch of channelsForCode(code)) {
                queueLine(ch, `[FlowConsoleCmds] "${entry.name}" connected (${entry.version})`);
            }
            return;
        }

        if (!entry) return;

        // ── log ────────────────────────────────────────────────────────────────
        if (pkt.type === 'log') {
            const line = (pkt.data || '').trim();
            if (!line) return;
            pushRecentLine(entry.code, line);
            for (const ch of channelsForCode(entry.code)) queueLine(ch, line);
            return;
        }

        // ── stats ──────────────────────────────────────────────────────────────
        if (pkt.type === 'stats') {
            entry.players     = pkt.players     ?? entry.players;
            entry.maxPlayers  = pkt.maxPlayers  ?? entry.maxPlayers;
            entry.playerNames = pkt.playerNames ? [...pkt.playerNames] : entry.playerNames;
            entry.tps         = pkt.tps         ?? entry.tps;
            entry.ram         = pkt.ram         ?? entry.ram;
            entry.uptime      = pkt.uptime      ?? entry.uptime;
            entry.chunks      = pkt.chunks      ?? entry.chunks;
            entry.entities    = pkt.entities    ?? entry.entities;
            entry.plugins     = pkt.plugins     ?? entry.plugins;
            return;
        }

        // ── pong ───────────────────────────────────────────────────────────────
        if (pkt.type === 'pong') {
            entry.players    = pkt.players    ?? entry.players;
            entry.maxPlayers = pkt.maxPlayers ?? entry.maxPlayers;
        }
    });

    ws.on('close', () => {
        if (!entry) return;
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

discordClient.once(Events.ClientReady, (c) =>
    console.log(`[Discord] Logged in as ${c.user.tag}`)
);

// Auto-unlink deleted channels
discordClient.on(Events.ChannelDelete, (channel) => {
    if (!links[channel.id]) return;
    const link = links[channel.id];
    const conn = connections.get(link.code);
    if (conn) conn.ws.send(JSON.stringify({ type: 'unlinked' }));
    delete links[channel.id];
    writeJson(LINKS_FILE, links);
    console.log(`[Discord] Channel ${channel.id} deleted – auto-unlinked from "${link.label}"`);
});

discordClient.on(Events.InteractionCreate, async (interaction) => {
    if (!interaction.isChatInputCommand()) return;

    const { commandName, channelId, guildId, member } = interaction;

    // ── /register ─────────────────────────────────────────────────────────────
    if (commandName === 'register') {
        if (guildId !== REGISTER_GUILD || interaction.user.id !== REGISTER_USER) {
            return interaction.reply({ content: 'You are not allowed to use this command.', ephemeral: true });
        }
        const targetId = interaction.options.getString('guild_id').trim();
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
            return interaction.reply({ content: 'You are not allowed to use this command.', ephemeral: true });
        }
        const targetId = interaction.options.getString('guild_id').trim();
        allowedGuilds = allowedGuilds.filter(id => id !== targetId);
        writeJson(GUILDS_FILE, allowedGuilds);
        return interaction.reply({ content: `Guild \`${targetId}\` has been unregistered.` });
    }

    // ── /registered ───────────────────────────────────────────────────────────
    if (commandName === 'registered') {
        if (guildId !== REGISTER_GUILD || interaction.user.id !== REGISTER_USER) {
            return interaction.reply({ content: 'You are not allowed to use this command.', ephemeral: true });
        }
        const list = allowedGuilds.length
            ? allowedGuilds.map(id => `\`${id}\``).join('\n')
            : 'No guilds registered yet.';
        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x5865f2).setTitle('Registered Guilds').setDescription(list)]
        });
    }

    // ── Admin permission check for all remaining commands ─────────────────────
    if (!member.permissions.has(PermissionFlagsBits.Administrator)) {
        return interaction.reply({ content: 'You need the Administrator permission to use this command.', ephemeral: true });
    }

    // ── /help ─────────────────────────────────────────────────────────────────
    if (commandName === 'help') {
        return interaction.reply({
            embeds: [new EmbedBuilder()
                .setColor(0x5865f2)
                .setTitle('FlowDiscordConsoleCmds – Setup Guide')
                .setDescription('Bridge your Minecraft server console directly into Discord.')
                .addFields(
                    { name: '1.  Download the plugin',
                      value: 'Get the latest `FlowDiscordConsoleCmds.jar` from GitHub Releases:\n> https://github.com/johann123-a11y/flow-discord-console-cmds/releases\nOr build it: `cd minecraft-plugin && mvn package`' },
                    { name: '2.  Install & configure',
                      value: 'Drop the `.jar` into `plugins/`, start the server, then edit `plugins/FlowDiscordConsoleCmds/config.yml`:\n```yaml\nconnection:\n  bot-url: "ws://YOUR_VPS_IP:8080"\n  server-name: "My Server"\n```' },
                    { name: '3.  Get your link code',
                      value: 'The server console shows:\n```\n[FlowConsoleCmds] Link code: XXXX-XXXX\n[FlowConsoleCmds] Run /link XXXX-XXXX in Discord\n```' },
                    { name: '4.  Link this channel',
                      value: '```\n/link code:XXXX-XXXX label:My Server\n```' },
                    { name: 'Commands',
                      value: '`/link` `/unlink` `/console` `/status` `/servers`' },
                    { name: 'In-game commands',
                      value: '`/flowcmds status | code | reconnect | reload | reset confirm`' },
                    { name: 'Requirements',
                      value: '- VPS port **8080** open in firewall\n- Administrator permission in Discord' }
                )
                .setFooter({ text: 'FlowDiscordConsoleCmds • Bukkit/Spigot/Paper' })
            ]
        });
    }

    // ── /link <code> [label] ──────────────────────────────────────────────────
    if (commandName === 'link') {
        const code  = interaction.options.getString('code').toUpperCase().trim();
        const label = interaction.options.getString('label') || code;

        links[channelId] = { code, label };
        writeJson(LINKS_FILE, links);

        const conn = connections.get(code);
        if (conn) conn.ws.send(JSON.stringify({ type: 'linked' }));

        // Post the last 30 buffered lines so the channel doesn't start empty
        const recent = recentLines.get(code) || [];
        if (recent.length > 0) {
            const text = recent.map(colorLine).join('\n');
            await interaction.reply({
                embeds: [new EmbedBuilder().setColor(0x2ecc71).setTitle('Server Linked')
                    .setDescription(`This channel is now linked to **${label}** (\`${code}\`).\n` +
                        (conn ? 'Server is **online**.' : 'Server is **offline** – will connect when the plugin starts.'))
                ]
            });
            for (let i = 0; i < text.length; i += 1990) {
                await interaction.channel.send('```ansi\n' + text.slice(i, i + 1990) + '\n```');
            }
            return;
        }

        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x2ecc71).setTitle('Server Linked')
                .setDescription(`This channel is now linked to **${label}** (\`${code}\`).\n` +
                    (conn ? 'Server is **online**.' : 'Server is **offline** – will connect when the plugin starts.'))
            ]
        });
    }

    // ── /unlink ───────────────────────────────────────────────────────────────
    if (commandName === 'unlink') {
        const link = links[channelId];
        if (!link) return interaction.reply({ content: 'This channel is not linked to any server.', ephemeral: true });
        const conn = connections.get(link.code);
        if (conn) conn.ws.send(JSON.stringify({ type: 'unlinked' }));
        delete links[channelId];
        writeJson(LINKS_FILE, links);
        return interaction.reply({ content: `Unlinked from **${link.label}**.` });
    }

    // ── /status ───────────────────────────────────────────────────────────────
    if (commandName === 'status') {
        const link = links[channelId];
        if (!link) return interaction.reply({ content: 'This channel is not linked.\nUse `/link <code>`.', ephemeral: true });

        const conn = connections.get(link.code);
        if (conn) conn.ws.send(JSON.stringify({ type: 'ping' }));

        const embed = new EmbedBuilder()
            .setColor(conn ? 0x2ecc71 : 0xe74c3c)
            .setTitle(link.label + ' – Status')
            .addFields(
                { name: 'Status',    value: conn ? 'Online'  : 'Offline',                      inline: true },
                { name: 'Link Code', value: `\`${link.code}\``,                                 inline: true },
                { name: 'Version',   value: conn ? conn.version       : 'N/A',                  inline: true }
            );

        if (conn) {
            const playerList = conn.playerNames.length ? conn.playerNames.join(', ') : 'None';
            embed.addFields(
                { name: 'Players',  value: `${conn.players}/${conn.maxPlayers} – ${playerList}`, inline: false },
                { name: 'TPS',      value: conn.tps,      inline: true },
                { name: 'RAM',      value: conn.ram,      inline: true },
                { name: 'Uptime',   value: conn.uptime,   inline: true },
                { name: 'Chunks',   value: String(conn.chunks),   inline: true },
                { name: 'Entities', value: String(conn.entities), inline: true },
                { name: 'Plugins',  value: String(conn.plugins),  inline: true }
            );
        }

        return interaction.reply({ embeds: [embed] });
    }

    // ── /servers ──────────────────────────────────────────────────────────────
    if (commandName === 'servers') {
        if (connections.size === 0) {
            return interaction.reply({ content: 'No servers are currently connected.', ephemeral: true });
        }
        const lines = [...connections.values()].map(c =>
            `**${c.name}** \`${c.code}\` – ${c.players}/${c.maxPlayers} players | ${c.version}`
        ).join('\n');
        return interaction.reply({
            embeds: [new EmbedBuilder().setColor(0x5865f2).setTitle('Connected Servers').setDescription(lines)]
        });
    }

    // ── /console <command> ────────────────────────────────────────────────────
    if (commandName === 'console') {
        const link = links[channelId];
        if (!link) return interaction.reply({ content: 'This channel is not linked.\nUse `/link <code>` first.', ephemeral: true });

        const conn = connections.get(link.code);
        if (!conn || conn.ws.readyState !== WebSocket.OPEN) {
            return interaction.reply({ content: 'The Minecraft server is not connected right now.', ephemeral: true });
        }

        const command = interaction.options.getString('command').trim();
        conn.ws.send(JSON.stringify({ type: 'exec', data: command }));

        return interaction.reply({
            embeds: [new EmbedBuilder()
                .setColor(0x2ecc71)
                .setDescription(`\`> ${command}\``)
                .setFooter({ text: `Sent by ${interaction.user.tag} → ${link.label}` })
                .setTimestamp()
            ]
        });
    }
});

discordClient.login(process.env.DISCORD_TOKEN);
