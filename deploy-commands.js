require('dotenv').config();

const { REST, Routes, SlashCommandBuilder, PermissionFlagsBits } = require('discord.js');

const REGISTER_GUILD = process.env.ADMIN_GUILD_ID || '1482117940064289013';

// Available in every Discord server
const globalCommands = [
    new SlashCommandBuilder()
        .setName('help')
        .setDescription('How to set up and link a Minecraft server'),

    new SlashCommandBuilder()
        .setName('link')
        .setDescription('Get a code to link this channel to a Minecraft server')
        .addStringOption(o => o.setName('label').setDescription('Name shown for the server (default: its server-name)').setRequired(false).setMaxLength(64)),

    new SlashCommandBuilder()
        .setName('unlink')
        .setDescription('Unlink this channel from its Minecraft server'),

    new SlashCommandBuilder()
        .setName('status')
        .setDescription('Show status, player list, TPS, RAM and uptime of the linked server'),

    new SlashCommandBuilder()
        .setName('servers')
        .setDescription('Show all currently connected Minecraft servers'),

    new SlashCommandBuilder()
        .setName('console')
        .setDescription('Execute a command on the linked Minecraft server console')
        .addStringOption(o => o.setName('command').setDescription('Console command (e.g. list, say Hello, op Player, stop)').setRequired(true).setMaxLength(1000)),
]
    // Only visible to administrators, never in DMs
    .map(c => c.setDefaultMemberPermissions(PermissionFlagsBits.Administrator).setDMPermission(false).toJSON());

// Only exist in the admin guild
const adminCommands = [
    new SlashCommandBuilder()
        .setName('register')
        .setDescription('Authorize a Discord server to use FlowConsoleCmds')
        .addStringOption(o => o.setName('guild_id').setDescription('Server ID to authorize').setRequired(true).setMaxLength(20)),

    new SlashCommandBuilder()
        .setName('unregister')
        .setDescription('Remove a Discord server from the authorized list')
        .addStringOption(o => o.setName('guild_id').setDescription('Server ID to remove').setRequired(true)),

    new SlashCommandBuilder()
        .setName('registered')
        .setDescription('List all authorized Discord servers'),
].map(c => c.toJSON());

const rest = new REST({ version: '10' }).setToken(process.env.DISCORD_TOKEN);

(async () => {
    try {
        console.log('Registering global commands...');
        await rest.put(Routes.applicationCommands(process.env.CLIENT_ID), { body: globalCommands });

        console.log('Registering admin commands in', REGISTER_GUILD);
        await rest.put(Routes.applicationGuildCommands(process.env.CLIENT_ID, REGISTER_GUILD), { body: adminCommands });

        console.log('Done.');
    } catch (err) {
        console.error(err);
        process.exit(1);
    }
})();
