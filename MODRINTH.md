<p align="center">
  <img src="https://raw.githubusercontent.com/valentinlehmann/fabric-vlc-stream/main/assets/icon.png" alt="vlc-stream" width="128" />
</p>

# vlc-stream

Stream **anything VLC can open** — Twitch, YouTube via `yt-dlp`, IPTV, RTSP cameras, a local `.mp4`, an `.m3u8` — onto a flat screen in your Minecraft world. The server picks the URL, position and size; every client renders the same screen, in sync, with spatial-ish audio that fades with distance.

Great for movie nights, in-world security cameras, ambient screens in a build, or just goofing off on a friends' server.

---

## ⚠️ You need to install VLC first

This mod uses your computer's VLC installation to decode video. **Without VLC, the screen stays black.**

- **Windows** — download the 64-bit installer from [videolan.org](https://www.videolan.org/vlc/) and install normally.
- **macOS** — install VLC.app from [videolan.org](https://www.videolan.org/vlc/) into `/Applications`, or `brew install --cask vlc`.
- **Linux** — `sudo apt install vlc` / `sudo dnf install vlc` / `sudo pacman -S vlc`.

Make sure VLC actually opens once before launching Minecraft. The mod auto-discovers the native libraries from the standard install location — no extra config needed.

> Only the **clients** need VLC. A dedicated server hosting the world does **not** need it installed.

---

## Commands

All commands are server-side and require op level 2 (gamemaster). The stream is **shared**: everyone connected sees and hears the same thing.

```
/vlc url <url>              # set the stream URL (also enables the screen)
/vlc pos <x> <y> <z>        # move the screen's centre
/vlc size <width> <height>  # resize, in blocks
/vlc rot <yaw> <pitch> [roll]
/vlc hearing <distance>     # max audible distance in blocks
/vlc enable | disable       # show/hide the screen entirely
/vlc play | pause | stop    # transport controls
/vlc seek <time>            # seek to seconds, mm:ss, or hh:mm:ss
/vlc show                   # print the current screen config
```

Settings persist across restarts. Clients without the mod simply ignore the screen — they won't crash or get kicked.

---

## Client settings

In **Options → Music & Sounds** you'll find:

- a **VLC Stream** volume slider, and
- a **Spatial Audio** toggle:
  - **OFF (default)** — VLC plays through your OS directly. Best quality, distance-based volume only.
  - **ON** — audio is mixed per-sample for stereo panning + distance roll-off. Better "screen in the world" feel, may add faint hiss on some setups.

---

## For modders

There's a small public API for controlling the screen from another mod — same effect as running the commands, but programmatic:

```java
import de.valentinlehmann.VLCScreenApi;

VLCScreenApi.setUrl(server, "https://example.com/stream.m3u8");
VLCScreenApi.setPosition(server, 100.5, 70.0, -42.0);
VLCScreenApi.setSize(server, 8.0f, 4.5f);
VLCScreenApi.setRotation(server, 180f, 0f, 0f);
VLCScreenApi.play(server);
VLCScreenApi.seek(server, 30_000L); // 30s
```

Under the hood it's three custom-payload packets in the `vlc-stream` namespace — `screen` (full config), `playback` (play/pause/stop) and `seek` (absolute ms). Send your own if you need finer control; clients without the mod just drop them.

---

## Source & issues

GitHub: **<https://github.com/valentinlehmann/fabric-vlc-stream>**

Bug reports, feature requests and PRs welcome. If something doesn't work, please attach your client log — 9 times out of 10 it's a VLC install path issue and the log will say so.
