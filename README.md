# DABOIS Link

A Fabric mod for Minecraft Java Edition 26.3 that adds **Pair server** to the Multiplayer screen. Players install one host-specific JAR in their Fabric profile, enter the server's shared four-digit code once, and join from their saved server list. The mod uses a direct TLS connection for Minecraft traffic. It needs no VPN app, relay subscription, or Fabric API when the host is publicly reachable.

## Get started

- **Hosting?** Use **Host DABOIS** for Windows, macOS, or Linux, or follow the manual host setup below.
- **Joining?** Ask your host for their invite JAR. Use **Play DABOIS** to install it into a Fabric profile, or put it in your existing Fabric profile's `mods` folder.

The **Embedded ZeroTier build** workflow produces the generic mod and setup ZIPs. Each server host generates their own invite. Keep server certificates, pairing files, and VPN identities out of public repositories and issue reports.

Servers created by Host DABOIS enable Minecraft account authentication. The manual host instructions cover opt-in offline profiles.

## Host setup

1. Install Fabric Loader 0.20.1 or newer for Minecraft 26.3 on the dedicated server. Put `dabois-link.jar` in its `mods` directory. Use the server directory as the working directory for the following command.
2. Generate the friends JAR. Replace `play.example.net` with a public DNS name or IP that reaches the server. The last argument is the local listener port and can be omitted when it matches the public port.

   ```sh
   java -cp mods/dabois-link.jar link.dabois.HostSetup play.example.net 25576 DABOIS 25576
   ```

3. Allow inbound TCP 25576 for the mod. If the server is behind a router, forward the port to it. Keep Minecraft's usual port private. For HMCL offline profiles, set `server-ip=127.0.0.1` and `online-mode=false` in `server.properties`. The tunnel enforces the profile name bound to each credential. Keep the vanilla port on loopback so clients have to go through that check. An existing ZeroTier-only listener needs a planned cutover. Switching a running vanilla server to Fabric requires a restart.
4. Start the Fabric server. Share only `dabois-link-friends.jar` with players. Keep `config/dabois-link` on the server private. Save a backup of this directory to retain pairings and the server's certificate across migrations.
5. In the server console, run `dablink code`. Share that same code with everyone you want to invite. It lasts 15 minutes. `dablink rotate` issues a new code and keeps existing devices paired. Use `dablink revoke ExactPlayerName` to reset a profile so it can pair a different device.
6. In offline mode, a player using an existing operator name enters the same code. They then give you the eight-character device ID shown in their pairing screen. Run `dablink approve ExactPlayerName DeviceID`, then have them press **Pair** again. This protects operator profiles from someone else claiming the name just by knowing the shared code.

The standard TLS route needs a reachable address or relay. Builds that bundle embedded ZeroTier can also connect through a private ZeroTier network. Keep a reachable fallback for devices waiting for network authorization.

## Players

Add the JAR from your host to the Minecraft 26.3 Fabric profile's `mods` folder. Open **Multiplayer → Pair server**, enter the code from your host, then join the new saved server. Use the same Minecraft profile name on future launches. If you move to another device, ask for a new code.

The saved entry uses a local address because the mod forwards the connection for you. Keep the mod and its pairing file when moving your Minecraft profile.

For Simple Voice Chat, put the matching voice chat mod in the server and client profiles. Leave the server's `voice_host` empty. Set `voicePort` in `config/dabois-link/server.properties` to the voice mod's UDP port, then regenerate the friends JAR and restart. With that JAR, DABOIS Link carries voice over its own authenticated connection and sends it to the voice mod's local UDP listener. Players can use the same public address for gameplay and voice.

## Server Admin

A paired Super Operator opens **Multiplayer → Server admin** to manage the server inside Minecraft. The Players, Worlds, Mods and Settings tabs talk to the server over the same encrypted connection. Dashboard access is attached to the paired device, so reusing a player's offline name or join code cannot grant access.

Run the following from the server directory to grant the first paired device access.

```sh
java -cp mods/dabois-link.jar link.dabois.AdminBootstrap ExactPairedPlayerName
```

Use the dashboard to grant or remove Super Operator access, change in-game operator levels, ban or unban players, and set live difficulty. Worlds, server settings, and enable/disable changes to installed mod JARs are staged. The dashboard reserves ingress and management credentials for the host, and keeps the DABOIS Link JAR enabled. Install new third-party JARs directly on the host.

The systemd files in `deploy/` allow the dashboard to apply staged changes and restart. They use the DABOIS server directory, service user, and service name. Adjust those paths for another host. Install the companion script and units as root, enable the path unit, and create `config/dabois-link/restart.enabled` in the Minecraft server directory. The companion saves a copy of the active world before a restart and reverts settings and mod state if the link fails to start.

## Build

### Setup packages

The **Play DABOIS** installers let players install a fresh copy of HMCL or add an isolated Fabric profile to an existing HMCL or Minecraft Launcher installation. Use the invite JAR from the host, or bundle it when packaging with `-FriendsJar`. The **Host DABOIS** installers create a fresh Fabric server, generate an invite, and open a server console with controls to show the pairing code and save and stop. Reopen this console with the **Start DABOIS** shortcut in the server folder. Hosting requires accepting the Minecraft EULA and choosing a route remote players can reach. The online installers download a verified Java runtime during setup.

Build the six setup ZIPs with `pwsh -File setup/package.ps1 -ModJar build/dabois-link.jar -GsonJar <gson.jar>`. They appear under `build/setup-packages`. Pass the multi-platform mod to include embedded ZeroTier on every supported platform.

The separate Mac offline installer bundles HMCL, Fabric, the configured DABOIS Link invite, and voice chat. It lets players set up a fresh HMCL folder or add the profile to an existing launcher. Cached Minecraft 26.3 files can be reused. On a clean Mac, Java 25 or later is required to launch HMCL, and uncached game files are downloaded on the first online launch. Run `setup/offline-mac/build-mac-offline.ps1 -FriendsJar <friends.jar>` to package it. The invite must include the Mac libraries for Apple Silicon and Intel. After preparing the profile, the installer offers to remove the standalone ZeroTier app using the vendor uninstaller. Keeping ZeroTier is the default. If the player chooses to remove it, the installer can carry the local ZeroTier identity into the new profile.

From this directory, run `pwsh -File build.ps1` with JDK 25. The build downloads Minecraft and Fabric build dependencies from their official sources. To reuse an installed client and libraries, pass `-MinecraftJar <path> -LibraryDirectory <path>`. The mod JAR is written to `build/dabois-link.jar`.

### Embedded ZeroTier

Run **Embedded ZeroTier build** in GitHub Actions to produce a JAR containing Windows x64, Linux x64, and macOS Intel and Apple Silicon libraries. For a local build, check out upstream [libzt 1.8.10](https://github.com/zerotier/libzt/tree/a6f6886a8c8c07249f9e5d0cae431848727fe845) with its submodules and build `native/zerotier` using CMake with `-DLIBZT_SOURCE=/absolute/path/to/libzt` and JDK 25. Install the resulting library and its upstream notices under `dabois-zt/<platform>-<arch>/` inside a resources directory. Pass that directory to `build.ps1 -NativeResources`.

This build uses the ZeroTier SDK inside Minecraft. The custom native layer joins a single configured network, uses TCP sockets only, enables TCP keepalive and disables Nagle's algorithm, and avoids automatic router port mapping. It creates no operating-system VPN adapter and runs no ZeroTier tray app or service. The server's existing certificate pin and device pairings still gate Minecraft, voice, and admin traffic.

1. Use a private ZeroTier network with IPv4 assignment. Put `ztNetwork=<16-digit-network-id>` and `ztHost=<embedded-server-managed-ipv4>` in the server's `config/dabois-link/server.properties`. If the managed address is not assigned yet, enter an intended free address, start the server, and assign that address to the embedded node in ZeroTier Central.
2. Set `server-ip=127.0.0.1` in Minecraft's own `server.properties` before retiring a system ZeroTier service. Start Minecraft with the native-enabled JAR and authorize the node ID printed at startup. Its identity is stored under `config/dabois-link/embedded-zerotier` and is separate from any previously installed ZeroTier service.
3. Generate the friends JAR with `HostSetup` and a reachable public fallback endpoint. Clients start their embedded node when they open Multiplayer. Authorize the node ID shown on **Pair server**. Players do not need a separate ZeroTier application or a ZeroTier account. Connections fall back to the existing invite route when the embedded transport is unavailable or cannot connect.

## Test

The transport test covers certificate pinning, a shared code, rotation, operator approval, saved credentials, status traffic, profile binding, and revocation. Run it in a disposable directory on JDK 25.

```sh
javac -cp /path/to/build/dabois-link.jar -d classes /path/to/tests/LinkTest.java
java -cp /path/to/build/dabois-link.jar link.dabois.HostSetup 127.0.0.1 35576 Test
java -cp 'classes:dabois-link-friends.jar' link.dabois.LinkTest
```

On Windows use `;` as the Java classpath separator. To measure actual gameplay, compare round-trip ping and server `tick query` figures with four players exploring freshly generated terrain. A direct connection can reduce relay delay. It cannot speed up the server's world simulation.

## Third-party software

The build uses the upstream licenses and notices for Fabric, Gson, and the ZeroTier SDK and its dependencies. The setup bundles verify the pinned HMCL and Simple Voice Chat downloads before packaging. Minecraft game files are downloaded from Mojang during online setup or reused from the player's cache. No license has been selected for the original DABOIS Link source.
