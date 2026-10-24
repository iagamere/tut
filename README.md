# PS4 Download Monitor 6.0.0 (v6)

## 6.0.0 speed
Found by reading the code (no profiler was available), so the gains are expected, not measured:
- **Connections are reused.** Every request to the PS4 server used to open a new TCP connection and close it (`disconnect()` after each call), which is slow over Wi-Fi when a screen sends dozens of requests. GET requests now keep their connection (POST still closes it, so a restarted server is not hit by a stale one).
- **Games list:** each game's param.sfo was read one after the other (up to 300 requests); now six at a time. The result is kept for 60 s (refresh or a deletion forces a new scan).
- **Captures (screenshots/videos):** folders were listed one by one; now one level at a time with the folders of a level in parallel; kept for 30 s.
- **Trophies:** the search for a game's trophy folder by id could list hundreds of folders; now at most 14 listings, breadth first, stopping at the first hit. What was read for a game is kept for 5 minutes, so reopening it is instant.
- Not changed: picture thumbnails are still read whole through the server (inherently slow for large screenshots; they were already limited to 3 at a time and cached in memory and on disk). Not compiled here (no Android SDK).

# PS4 Download Monitor 5.9.17

## 5.9.17 vector icons instead of emoji
- The only emoji / symbol icons left in the interface were the lock and the check mark in the trophy list (TrophiesUi) and the check mark next to a likely PS4 in Find my PS4 (SettingsUi). They are now the app's vector drawables (ic_lock, ic_check_circle: Android's own format for SVG paths). Plain text separators ("•", "→", "⋮" inside sentences) were left as text on purpose. No other emoji was found in the Kotlin code or the XML resources. Not compiled here.

# PS4 Download Monitor 5.9.16

## 5.9.16 trophy pictures instead of coloured dots
- The platinum / gold / silver / bronze pictures supplied by the user (res/drawable-nodpi/trophy_*.png, reduced to 96x121 px) replace the coloured circles next to each trophy and in the per-grade counters. A grey dot stays for a trophy whose grade is unknown. Not compiled here.

# PS4 Download Monitor 5.9.15

## 5.9.15 build fix
- 5.9.13/5.9.14 failed to compile (the user's GitHub Actions log): the `trophySync` field had been added to the private `V2Info` class instead of `ServerInfo` (two errors: Ps4Server.kt:144, TrophiesUi.kt:114). Fixed; those were the only compile errors in the log.

# PS4 Download Monitor 5.9.14

## 5.9.14 trophy names from TROP.ESFM
- Games not synced with PSN showed "Trophy 000..034" because the pack's names live in `TROP.ESFM`, which is AES-encrypted and was never tried (the code only looked for PS3 `.SFM` entries). `TrophyLib.decryptEsfm` follows the PS4 developer wiki: key = AES-128-CBC(trophy key 21F41A6B..., zero IV, NP id zero-padded to 16 bytes); file = 16-byte IV + AES-128-CBC XML. Two readings of the open details (key-generation IV, where the IV sits) are tried; the result must contain `<trophyconf` or the failure is written into the technical notes (with the first bytes).
- NOT verified against a real ESFM file (none was available here). Not compiled (no Android SDK). If names still show as numbers, copy the technical details and send them.

# PS4 Download Monitor 5.9.13

## 5.9.13 trophy export built into the server (abdo17), no per-game button
- Bundled server is now `2.0.0-abdo17`: new routes `POST /v2/trophy/sync` (starts a background export, returns at once) and `GET /v2/trophy/status`. It goes through every account's `/user/home/<id>/trophy/data/NPWR#####_##/`, mounts each `trophy.img` (the method the stand-alone helper used successfully on the user's console) and copies the files to `/data/ezremote-client/trophy_export/<account>_<NPWR>/`. A `_stamp` file (size + mtime of the image after our own unmount) makes later runs skip unchanged images. `_BK` folders are ignored. `/v2/info` now has `"trophy_sync": 1`.
- App: opening any game's trophies runs the sync once (not again for 2 minutes) and then reads the export, so no button per game. A "Refresh from the console" button forces a new copy after you earn something. With an older server the 5.9.10 helper panel is still shown.
- NOT verified: the mount code has only run as a separate payload, never inside the server; it runs in a background thread, so a failed mount cannot block downloads, but a crash inside the console's mount call would still stop the server. The app is not compiled (no Android SDK). The server links (Orbis toolchain) but was not run.

# PS4 Download Monitor 5.9.12

## 5.9.12 unsynced trophies are now read from the sealed image
- The 5.9.10/11 helper WORKED on the user's console (Hollow Knight, NPWR16485_00): sealedkey decrypted, trophy.img mounted, `TRPTITLE.DAT` (9856 B) and `TRPTRANS.DAT` (26064 B) copied, unmount 0x0.
- `TrophyLib.parseTrpTitle` reads TRPTITLE.DAT: table type 0x800 (35 entries, one per trophy) = id, unlocked flag, two times (microseconds since year 1). Checked on the real file: 19 of 35 unlocked, all times fall between 2026-09-23 and 2026-10-10 (plausible); grades in table 0x500 (1 P, 2 G, 3 S, 4 B). The unlock-time column choice (first of the two) and "flag = unlocked" are inferred from this one dump, not documented anywhere.
- `TrophyLib.state` uses the exported file for accounts whose game is not in trophy_net.db. After a successful run the panel refreshes the list by itself; the data is a snapshot: run the helper again to refresh after playing.
- TRPTRANS.DAT (transaction/sync data) is exported but not used.
- Not compiled here (no Android SDK).

# PS4 Download Monitor 5.9.11

## 5.9.11 fix: the sealed-trophy panel was hidden
- In 5.9.10 the "Read the sealed trophy file" panel only appeared when the unlocked map was null; for a game with no record it is an empty map (all trophies shown locked), so the panel never showed. It now appears whenever nothing could be read or nothing is earned (and an NPWR id is known).

# PS4 Download Monitor 5.9.10

## 5.9.10 experimental reader for games whose trophies are not synced (sealed trophy.img)
- Base: the 5.9.9 package from the other session (its TrophyLib/TrophiesUi changes are kept).
- Finding (from the user's console log): `/user/home/<id>/trophy/data/NPWR16485_00` holds only `sealedkey` (96 B) and `trophy.img` (2 MB). The progress of a game that was never synced with PSN lives only in that encrypted PFS image, so no file reader can see it.
- New helper payload `trophymount.elf` (source: `payload/trophymount/trophymount.c`, built with the Orbis toolchain, links without warnings, NEVER RUN on a console). It follows the open-source tool Apollo (apollo-ps4, GPLv3): decrypt `sealedkey` through `/dev/sbl_srv` (ioctl 0xC0845302), `sceFsMountSaveData` from `libSceFsInternalForVsh.sprx` with budget "system", copy the files inside to `/data/ezremote-client/trophy_export/<account>_<NPWR>/`, unmount. It does NOT change process credentials (Apollo does, through libjbc), so the console may simply refuse a step; each step and error code is written to `<export>/_result.txt` and `/data/ezremote_trophy.log`.
- App: under a game's trophies, when the progress could not be read, a panel "Read the sealed trophy file (experimental)" sends the helper, waits, re-sends the server if the loader replaced it, shows the report and offers "Copy report and files (hex)".
- NOT done: parsing the unlocked flags. The PS4 layout of TRPTITLE.DAT is only partly documented, so this version only exports and shows the files. Send the copied report so the parser can be written from real data.
- Not compiled here (no Android SDK).

# PS4 Download Monitor 5.9.5

## 5.9.5 tutorial, "?" help buttons, shutdown feature removed
- New first-run tutorial (Tutorial.kt): full-screen steps with Skip / Back / Continue / Done. Shown once; reopen it from Settings > Show the tutorial.
- Small round "?" buttons (HelpDot / SectionTitleHelp) on the server screen panels, Home "Server in use" and the Downloads queue explain what each control does.
- Removed: power off / sleep the PS4, and shutdown after a download finishes (it did not work). The standby/poweroff payloads and payload/ sources are gone. The old `powerOff` field in Models.kt/Store.kt is kept only so stored data still loads.
- NOT compiled here (no Android SDK in the sandbox) and never run on a phone.


## 5.9.4 the /data/payloads copy happens whenever the server is sent
- After a successful send of the server (Home > "Server in use" > Our server, and the same flow elsewhere), the app now also puts a copy at `/data/payloads/ezremote-server.elf` through GoldHEN's FTP server, by default. A failure (for example FTP is off in GoldHEN) never fails the send: it is only added as a note to the result message. Switch it off in Server screen > "Copy for GoldHEN AutoRun". The FTP port (default 2121) is saved there too.
- The automatic re-send when the server stops (every 5 minutes at most) does not place copies; only an explicit send does.
- Still manual, once: add the file to GoldHEN's AutoRun queue (LaunchPad > Square > Add to AutoRun queue). Nothing about GoldHEN's FTP login or AutoRun was verified on a console.
- Server unchanged (`2.0.0-abdo16`). NOT compiled here (no Android SDK).

## 5.9.3 put the server in /data/payloads (for GoldHEN AutoRun)
- **Server screen > "Start the server automatically":** uploads the built-in server (or the saved file) to `/data/payloads/ezremote-server.elf` through GoldHEN's FTP server (default port 2121, editable; turn FTP on in GoldHEN first). The file name is fixed, so an AutoRun entry made once stays valid after updates.
- **Not automated:** adding the file to GoldHEN's AutoRun queue. Do it once on the PS4: GoldHEN menu > LaunchPad > highlight `ezremote-server.elf` > Square > Add to AutoRun queue (steps taken from the READMEs of two third-party GoldHEN projects; not verified by me). The app does not write GoldHEN's own configuration.
- The upload is checked with the FTP `SIZE` reply when the server supports it. The FTP code (`FtpUpload.java`) was tested here against a small FTP server written for the test (success, login refused, store refused, short file, closed port). NOT tried against GoldHEN: its login rules are undocumented in what I found (the app tries `anonymous`).
- Bundled server: `2.0.0-abdo16` (retries the network start for about a minute, for AutoRun).
- NOT compiled here (no Android SDK). The Java file was compiled with javac only.

# PS4 Download Monitor 5.9.2

## 5.9.2 web page in the server (for iPhone and any browser)
- Bundled server is now `2.0.0-abdo15`: it serves a web page at `http://<PS4 IP>:6701/` (downloads, files with PKG install, server info; Arabic/English). Open it in Safari on an iPhone, or any browser. The app itself is unchanged apart from the bundled server.
- The page cannot send the server to the PS4 the first time (browsers cannot open a raw connection to port 9090): send it once from this app or any payload sender. It has no trophies, captures, games or settings screens.
- Tested in headless Chromium against the Linux test host only; NOT on a console, NOT on an iPhone. NOT compiled here (no Android SDK).

# PS4 Download Monitor 5.9.1

## 5.9.1 full power-off + diagnostic server
- **Settings > PS4 > "Turn off after downloads" > switch "Turn the PS4 off completely (not rest mode)":** when on, "Turn off now", the sleep timer and "Turn the PS4 off when the download finishes" send `poweroff.elf` (built from `payload`, bundled in the app) instead of `standby.elf`. Success = the console stops answering DDP twice in a row; if it goes to rest mode instead, the app says so.
- **After a full power-off the app cannot turn the PS4 on again** (DDP wake works only from rest mode): press the console's power button. Downloads stop.
- **NOT verified:** `poweroff.elf` calls `sceSystemStateMgrTurnOff` from libSceSystemStateMgr.sprx, and `standby.elf` calls `sceSystemStateMgrEnterStandby`. Both names and the module paths (three are tried) come from memory / the earlier standby source; I could not confirm them from documentation. If the function is missing the payload exits with code 2 and the app reports "still on". Both ELFs were linked here with the orbis toolchain but NEVER run on a console.
- Bundled server is now `2.0.0-abdo14` (diagnostics only: `alive N s`, `CRASH signal N`, `exit:` and `5b`/`6` lines in `/data/ezremote_boot.log`; the loop no longer spins in rest mode).
- Texts on some screens still say "rest mode" even when the full-off switch is on.
- NOT compiled here (no Android SDK).

# PS4 Download Monitor 5.9

## 5.9 turn the PS4 off when a download finishes
- **New switch "Turn the PS4 off when the download finishes"** in the send dialog (Download to PS4) and on each download's screen (while it is active or paused). It is saved with the download.
- **When it fires:** only after a marked download is COMPLETED and verified AND nothing else on that PS4 is still downloading (paused ones do not block). A failed download never turns the PS4 off. Then a 60 s countdown starts (banner on the Downloads screens + a notification, both with Cancel); a new download starting during it cancels the shutdown. The mark is used up once, so restarting the app never turns the PS4 off by surprise.
- **How the PS4 is turned off:** our server (`server.elf`) and the console's discovery protocol have no rest-mode command, so the app sends a small payload, `standby.elf`, to GoldHEN's payload loader (port 9090) like it does for the server, then checks that the console answers "rest mode" (or stops answering) within 40 s. Source: `payload/standby/standby.c` (calls `sceSystemStateMgrEnterStandby`). Put the built file in `app/src/main/assets/standby.elf` or choose it in the PS4's settings > "Turn off after downloads" (there is also a "Test: turn off now" button).
- **Settings > "Turn off the PS4":** "Turn off now" (with confirmation) and a sleep timer (15 min, 30 min, 1 h, 2 h, 4 h, 8 h, or custom minutes). Both put the PS4 in rest mode through the same standby payload; the timer ignores running downloads, shows a 60 s warning with Cancel, survives an app restart (dropped if it ran out >2 min ago), and is cancelled from Settings, the Downloads banner or the notification.
- **LIMITS:** `standby.elf` is NOT built here and the whole feature is NOT tried on a console; without the payload the switch shows a warning and nothing is turned off. NOT compiled (no Android SDK).

# PS4 Download Monitor 5.8

## 5.8 no ezRemote at all
- **How to use (first time):** 1) turn the PS4 on and start GoldHEN, 2) in GoldHEN settings turn on "Enable PayLoader Server" (port 9090), 3) open the app > Add PS4 (it finds the console by itself) > Home > tap "Server in use" > "Our server". Nothing else has to be installed on the PS4.
- Removed: every call to ezRemote Client's web server (port 8080), the "PS4 web port" field, the web status on Home, the 8080 scan in "Find my PS4", and every mention of ezRemote in the texts. All file, download, install, copy, move, delete and information functions go through our server (port 6701).
- Renamed in the code: `EzRemote` -> `Ps4Api` (server only), `EzServer` -> `Ps4Server`, `EzError` -> `Ps4Error`.
- Not available any more (the server has no such call): extracting archives and editing text files on the PS4.
- After a successful send the app turns file access on and creates `/data/ezremote-client` on the PS4 (best effort): the bundled server.elf still uses that folder name and `/data/ezremote_boot.log` for its history and log; they are paths inside the binary and cannot be renamed without the server's source.
- NOT compiled here (no Android SDK) and NOT tried on a console. Static checks only: brace balance and that every called function exists.

# PS4 Download Monitor 5.7.5

## 5.7.5 storage browser, safe delete, delete a game
- **Storage tab > Browse files:** lists every folder the server may read (the same list as Device > "Folders the app may read"), with each file's size and date. "Measure this folder" / "Size" add up a folder by walking it (stops at 3000 folders). Every row shows a lock when this app would never delete it; tap the lock for the reason.
- **Deleting is OFF by default** (Storage tab > Deleting > "Allow deleting from this app"). Nothing is deleted from the Storage or Games screens until you switch it on.
- **What can be deleted** (`DeletePolicy.kt`, one place, re-checked right before every request): only items strictly INSIDE the capture folders, the PS4's download folder (from the PS4 settings) or a USB drive (/mnt/usbN, not folders that look like console data). One confirmation per item.
- **Never deleted, whatever is tapped:** top-level folders, /system*, /user/home (accounts), /user/system, trophies, save data, /data/GoldHEN, /data/etaHEN, /data/ezremote-client, /data/payloads, installed game folders, eboot.bin / param.sfo / sce_sys / sce_module / *.sprx / *.self / *.prx, the folder roots themselves, and any path with "..", empty or relative parts. A misconfigured download folder (e.g. /data or a system path) is ignored as a zone. Tested with ~100 cases (`DeletePolicy` runs on the JVM alone).
- **Delete a game:** Games tab > tap a game > "Delete this game…" (CUSA ids only; system apps NPXS never). Two dialogs: the first says what goes and what stays, the second is a "Final warning" whose button unlocks after 4 s. It removes `/user/app/<id>`, `/user/patch/<id>` and `/user/addcont/<id>`. Saves, trophies, captures and the title's artwork/metadata are NOT touched.
- **Important limit:** our server has no "uninstall" call, so the console's application database is not edited. After deleting, the game's icon can stay on the PS4 home screen; press Options > Delete on it there to clear it. Games installed on extended storage (/mnt/ext0) are not handled.
- While deleting, the server's writable folders are widened only for that operation (`/user/app`, `/user/patch`, `/user/addcont` for a game; the zone for a file) and put back afterwards. If the app is killed in the middle they stay open until the next change.
- If the server's own recursive delete fails, the app deletes the folder piece by piece (files, then folders), checking the policy for every path.
- NOT compiled here (no Android SDK) and NOT tried on a console: especially that /v2/fs/delete removes a non-empty folder in one call (the fallback exists in case it does not), and the game-delete result on the home screen.

# PS4 Download Monitor 5.7.4

## 5.7.4 trophy files before/after
Device tab > "Trophy files: before / after": Snapshot, earn a trophy on the PS4, Compare. Read-only; reports which files changed (name, size, modified time). Content changes with identical size and time are not detected. Nothing about where unsynced trophies live is assumed.

## 5.7.3 trophies from trophy_net.db
- Your notes showed the real layout of `/user/home/<id>/trophy/db/trophy_net.db` (titles, trophies, users, user_title_map, user_trophy_map). The trophy screen now reads it: names, descriptions, hidden flags, earned marks with time, the progress % and per-grade counts of the account, and the trophy pictures (the file only holds web addresses, so the PHONE downloads them from the internet).
- This file is the copy synced with PSN: a game or an account that never synced its trophies is simply not in it (one of your accounts has 0 games, one has 1, one has 9). The unsynced local trophies live in encrypted `trophy/data/<title>/trophy.img` + `sealedkey`, which this app does not read.
- Grade numbers (platinum/gold/silver/bronze) are not documented: they are worked out by matching the game's own counts; if no mapping matches, grades are hidden and the note says so. When a database lists more than one user, the user with the most progress on that game is shown.
- NOT compiled, not verified on a console.

# PS4 Download Monitor 5.7.2

## 5.7.2 (goes with ezRemote Server 2.0.0-abdo13): trophy diagnostics
- Your log showed that `/user/home/<id>/trophy/db` holds only `trophy_net.db` on your console (no `trophy_local.db`). The reader now opens EVERY .db in that folder, uses the one that has the Apollo trophy tables, and otherwise writes each file's real tables, columns and row counts into the technical notes, plus the listing of the account's `trophy` folder. Nothing is guessed about `trophy_net.db`: its layout is unknown until the notes are seen.
- Server abdo13: /v2 errors keep their JSON text (the generic error page was replacing it, which is why you only saw "Unexpected reply (HTTP 400)") and an unreadable path now reports the real reason. Choose "Our server" once on the Home screen to send abdo13.
- NOT compiled. Not verified on a console.

# PS4 Download Monitor 5.7.1

## 5.7.1 trophies read from the console's own database
- **Why trophies did not show:** the reader looked for the game's NPWR id inside `tbl_trophy_flag.title_id`, but that column is a NUMBER (the row id of the game in `tbl_trophy_title`), so nothing ever matched; and the unlock time is text (`2024-05-01T12:30:00.00Z`), not a number.
- **Now (`TrophyLib.accountDb`):** each account's `/user/home/<id>/trophy/db/trophy_local.db` is copied (with its `-wal` file when present) and read with the layout used by the open-source tool Apollo (bucanero/apollo-ps4): `tbl_trophy_title` (NPWR id, title, counts, **progress %**) and `tbl_trophy_flag` (trophy id, **name, description**, grade 1-4, unlocked, visible, time). The game is matched by NPWR id, then by title.
- The trophy list, names, grades, earned marks, per-grade counts and the **console's own completion % per account** now come from that database; the game's .trp file is only used for the pictures (and as a fallback when the database cannot be read). Accounts that never played the game show "no trophy record".
- Still NOT verified on a console: that the server can hand over `trophy_local.db` (it is inside /user/home, a read-only folder the app already enables), that the console's SQLite version/journal state opens on Android, and whether names are in the console's language. If the screen still shows nothing, tap "Copy technical details" and send it.
- NOT compiled.

# PS4 Download Monitor 5.7

## 5.7 (goes with ezRemote Server 2.0.0-abdo12): install and copy through our server
- **Install** (every Install button) now goes through our server: `POST /v2/install`, progress from `GET /v2/install/status`. ezRemote Client does not have to be open. The package must be inside a folder the server may access (the download folder is by default). An already installed game is reported as an error, never uninstalled.
- **Copy / Move** use our server when it can (`/v2/fs/copy`, `/v2/fs/rename`, also across volumes); Extract and text edit still use ezRemote Client's web server; the old paths remain as a fallback when our server is not running.
- Needs the abdo12 server (bundled in the app): choose "Our server" on the Home screen once to send it. Older servers keep working for everything else.
- NOT tested on a console: the install calls follow ezRemote Client's local install (BGFT) but could not be run here. NOT compiled.

# PS4 Download Monitor 5.6.3

## 5.6 game library (captures + trophies per game)
- Tap a game: the sheet now has three tabs: **Details**, **Captures** (only that game's screenshots and videos, same viewer / save / delete as the Captures tab) and **Trophies**.
- **Trophies:** a row of account circles (profile picture inside a progress ring, name and %). Tap an account for its progress (% of the game's trophies, counts per grade, points) and the list split into Earned / Not earned yet, with the trophy pictures, names, descriptions and dates. Hidden trophies stay hidden until earned.
- Where each part comes from (`TrophyLib.kt`): accounts from /user/home + username.dat; avatar from /system_data/priv/cache/profile/<id>/avatar*.png (guess, falls back to the first letter); NPWR id from npbind.dat; trophy list/pictures from sce_sys/trophy/*.trp (documented layout).
- NOT VERIFIED on a console: (1) where each account's unlocked trophies are stored. The reader searches /user/home/<id>/trophy for SQLite files and matches tables by column names; if nothing matches the screen says so and lists what it found (use Device > Scan trophies and send the result so the reader can be fixed). (2) Trophy names are plain XML only in some packs; when encrypted the trophies are numbered (pictures are real). (3) The profile-picture path.
- NOT compiled.

# PS4 Download Monitor 5.5

## 5.5 rest mode
- **The app now knows the console's power state** (Home card: "PS4 power": On / Rest mode / No answer), asked with DDP (UDP 987, no pairing). The PS4 answers it in rest mode when "Stay Connected to the Internet" and "Enable Turning on PS4 from Network" are on (Settings > Power Save Settings > Set Features Available in Rest Mode).
- **Rest mode is no longer treated like a failure:** the download waits with the note "The PS4 is in rest mode...", polling continues, the alert says rest mode, and the download continues by itself when the server answers again.
- **Wake PS4:** Home button (shown in rest mode / no answer). Needs the console's wake key ("user-credential" of a registered Remote Play client) pasted once in the PS4's settings. Optional switch: wake automatically while a download is unfinished (max 3 tries, 2 min apart).
- Console on but server not running: the saved server file is re-sent after 15 s instead of 60 s.
- LIMITS: whether ezRemote Server keeps running / downloading inside rest mode is decided by the console and the payload, not by this app (NOT verified). Wake packet NOT tested on a real console. NOT compiled.

## 5.4.1
- Fixes the compile error in VideoStream.kt (wrong import of aspectRatio).

## 5.4
- **Delete captures from the PS4:** long-press to select (Delete button) or the trash icon in the full-screen viewer. Deleting goes through the server's `/v2/fs/delete`. The server refuses deletes in read-only folders ("Path is outside the writable folders (fs_roots)"), so before the first delete the app adds ONLY `/user/av_contents/photo` and `/user/av_contents/video` to the server's writable folders (`EzServer.allowCaptureDelete`, existing writable folders are kept). `EzServer.deleteCapture` refuses any path outside those two folders. Not undoable.
- **Play videos without downloading:** tap play in the viewer. `VideoStream.kt` feeds Android's MediaPlayer from `/v2/fs/read` (1 MiB blocks, next block fetched ahead, nothing saved on the phone). Play/pause and a seek bar; no fullscreen rotation yet. Codec support is whatever the phone's MediaPlayer supports.
- NOT compiled and NOT run on a phone or PS4. If the server does not accept the writable-folder change, the app shows the server's own answer.

## 5.3.2
- Captures are read ONLY from `/user/av_contents/photo` and `/user/av_contents/video` (Ps4Info.scanCaptures). Other folders under av_contents and the extra read-only folders are no longer scanned for captures. If those two folders do not exist on the console the list is empty and the technical notes say which path was refused.

## 5.3.1 = 5.2.3 (auto-discovery, clearer send errors) + the 5.3 PS4 information work
- Merged from your separate 5.3: Capture/Games screens, Thumbs fix, one-tap save to gallery, wider read-only folders (EzServer.RO_DEFAULT), manifest storage permission for Android 8/9, App.kt init.
- Deliberately NOT merged: the "Original ezRemote Server" choice added to ServerPicker in 5.3 (ezRemote is no longer needed).

## 5.3: PS4 information, fixed and rebuilt
- **Why no picture ever showed:** the image loader did its network read on the main thread; Android refuses that (NetworkOnMainThreadException) and the error was swallowed as "no image". `Thumbs` now runs on Dispatchers.IO, and small thumbnails are also cached on disk (instant the second time).
- **Captures:** thumbnail grid, tap = full-screen viewer (swipe between pictures, pinch / double-tap zoom, tap to hide the bars, full resolution up to 3072 px, files up to 64 MB). Videos get a thumbnail frame (best effort, a few MB read by range requests).
- **One-tap save:** the Save button (or long-press to select several / "Save all") copies the ORIGINAL bytes into the gallery album `DCIM/PS4 Monitor` through MediaStore: photos and videos in one album, no file picker. Android 10+ needs no permission; Android 8/9 asks for storage permission once. Saved items show a tick and open in the gallery / video player. The album name is `CaptureSaver.ALBUM` in Gallery.kt.
- **Games:** grid of square covers (icon0.png, 1:1) with name, id and version; tap = details sheet (background picture pic1/pic0, cover, Title ID, Content ID, type, version, content version, required firmware, parental level, install folder, copy id).
- NOT compiled here (no Android SDK) and not run on a console: expect to fix a compile error or two on first sync.


## 5.2.3 clearer "could not send" error
- Sending the server goes to GoldHEN's loader (port 9090) only; it never needs ezRemote. The old message wrongly said "ezRemote web server". Now it names port 9090 and shows which of 9090 / 6701 / 8080 answer right now.

## 5.2.2 find the PS4 automatically
- **Add PS4** now searches the network by itself (and has a "Find my PS4 on the network" button). `Discovery.kt` scans the phone's own /24 networks (Wi-Fi and hotspot) for ports 6701 (ezRemote Server, identified with /v2/info or /get_download_state), 9090 (GoldHEN payload loader) and 8080 (ezRemote web, weak evidence). Tap a result to fill the IP; if exactly one strong candidate is found and the field is empty, it is filled for you.
- Only scans /24 networks. NOT compiled and NOT run on a phone or PS4. Router "client isolation" / guest Wi-Fi blocks the scan.

Open in Android Studio (JDK 17), sync Gradle, run. NOT compiled by the author (no Android SDK available): expect to fix a few compile errors on first sync.

How it works (all verified from the ezRemote sources):
- Send: POST /__local__/download_url on ezRemote Client (:8080) with dest = FINAL FILE PATH (folder + unique name).
- Monitor: ezRemote Server (:6701) GET /get_download_state (live bytes/size/state); when it is down, bg_download_history.json.
- Pause / Resume / Delete, two modes (detected automatically with GET /v2/info on :6701):
  * CUSTOM server (ps4-ezremote-server-abdo, API /v2): POST /v2/pause, /v2/resume, /v2/delete {id,...} act on ONE download immediately;
    the other downloads keep running and nothing has to be restarted. Live list from GET /v2/downloads (server-measured speed, connection count,
    error text). A failed / "needs link" download is continued by sending the link again to the SAME file path (the Retry button does that).
  * ORIGINAL server: stop ezRemote Server, edit bg_download_history.json (state 3 + failed_attempts 5 / 1, or remove the entry), delete files.
    The server reads the file only at start-up, so a running server cannot be told to stop one download.
- Files: browse, rename, new folder, copy/cut/paste, extract, install, delete (ezRemote web only; no FTP).
- PKG reader: title, icon, header, entries (partial reads over ezRemote downloadFile with Range).

Files: Models, Store, EzRemote (client API), EzServer (+ BgHistory), DownloadMonitor, Ops, Pkg, Notifier, MonitorService,
Names, Fmt, Lang, and the UI (MainActivity, Ui, HomeUi, DownloadsUi, FilesUi, PkgUi, BrowserUi, SettingsUi).
Removed in 4.0: FTP, file-matching heuristics, Net size check, install-from-link, upload, text editor, save-to-phone, image preview,
artwork scan, history screen, auto-rename/auto-install, adoption toggle, "stop monitoring", speed chart, untracked .tmp list, events list.

## 5.2 (goes with ezRemote Server 2.0.0-abdo11): server choice + built-in Builder
- The Home card now shows **Server in use** (Original ezRemote / Our server + version). Tap it to choose: **Original**, **Our server** (abdo11 is bundled inside the app, no separate download) or **Build a server from my zip** (the PS4 Builder flow: the zip is built on GitHub Actions, the ELF is downloaded and sent to the PS4).
- PS4 Builder is no longer needed as a separate app.
- Sending retries by itself when the old server still answers, and a server that was already running is not mistaken for the new one.
- Choosing Original stops our server and forgets the saved ELF (otherwise the monitor would re-send it). Getting the original back may need "restart server" in ezRemote Client on the PS4: NOT verified.
- The build token is embedded in the app (scrambled, not secret: anyone can unpack the APK). NOT compiled or tested on a device.

## 5.1 (goes with ezRemote Server 2.0.0-abdo10): PS4 information
- New card on the Home screen opens **PS4 information**: screenshots and videos (thumbnails, full view, save to phone), storage (free/used per volume), installed games (title, id, version, icon from param.sfo) and device details (from the console's sysctl). Everything is read through ezRemote Server, so it works while a game runs.
- Needs the abdo10 server. The app sends it read-only folders (`/user/av_contents`, `/user/app`, `/user/appmeta`, `/system_data/priv/appmeta`) once; the Device tab lets you add more and has **Check paths**, which shows which folders the console actually answered.
- NOT verified on a console: these folder names come from public documentation. If a tab is empty, use Check paths and add the right folder.

## 5.0 (goes with ezRemote Server 2.0.0-abdo9)
- Queue control (custom server): download details show position and priority, with Start now / To top / Up / Down / To bottom and High / Normal / Low priority (`/v2/start_now`, `/v2/move`, `/v2/priority`).
- Files, covers, name checks, file sizes, rename / delete / new folder go through the server (`/v2/fs/*`, port 6701) for paths in /data and /mnt, so they work while a game runs; anything else, or an older server, falls back to ezRemote web (8080). Copy, move, extract, install and edit still need ezRemote web. The app switches file access on once; the Server screen has a switch to turn it off.
- Server token: field in the PS4 settings, sent as `X-Token` on every server call; the Server screen can set or remove the token on the server. A locked server is reported instead of looking "old".
- "Finished" and "interrupted" notifications use the card look (cover colour, name, bar).
- NOT compiled (no Android SDK here) and not run on a phone or PS4.

## 4.3 cards not silent, visible on the lock screen
- Channel `dl_v2`: IMPORTANCE_DEFAULT with no sound / vibration (so the cards are out of the "Silent" section), lock-screen visibility PUBLIC.
  The phone's own setting "show notifications on the lock screen" must also be on for this app.

## 4.3 one notification only
- Android requires a notification for the monitoring service and ColorOS shows it even on a blocked channel, so the foreground notification is now the
  LEAD download's card (`Notifier.foregroundFor / syncForeground / handoff`, `MonitorService.promote`). There is no separate "PS4 Monitor" row while a
  download card exists. When the lead card finishes / is deleted, the foreground state moves to the next card first; with no card left the quiet
  summary (id 1) is used for the few seconds until the service stops.

## 4.3 full-bleed cards (current): no grey box
- Android docs: apps targeting 31+ cannot create fully custom notifications; the system wraps them in its standard template (the grey container).
  With `targetSdk = 30` (compileSdk stays 34) a custom layout WITHOUT a style is the whole notification, so the cover-coloured surface runs edge to edge.
  `Notifier.FULL_BLEED` (layouts `notif_dl_*_fb.xml`) switches it; false (+ targetSdk 34) gives the decorated card inside the grey box again.
- The app draws its own header row (glyph, app name, time left) because the system draws none. The id-1 foreground notification stays a normal (grey) one.
- Not verified on a device by the author; ColorOS may still wrap or group notifications of one app.

## 4.3 media-session cards (tried, OFF by default)
- Tried a MediaSession notification to remove the grey container (`Notifier.mediaCard`, switch `USE_MEDIA_SESSION`). On ColorOS the system turned it into
  its own grey media player (no cover colour, no size / speed, bar stuck at 00:00 / 01:40), so the RemoteViews card (`customCard`) is the default again.
  The grey container around any normal notification is drawn by System UI and cannot be removed by the app.

## 4.3 notification redesign (cards)
- **Real progress bar.** The block characters are gone. Each download card is a custom `RemoteViews` layout (`DecoratedCustomViewStyle`, so the
  system header with the app name stays) containing a real determinate `ProgressBar` (rounded fill over a rounded track, 0..1000 for smooth steps).
- **Cover colour.** `CoverColor.palette()` derives a dark tonal surface + a vivid accent (>= 4.5:1 contrast) from the cover's dominant colour.
  The card surface, progress fill, percentage and buttons use it. Android 12+: the bar is tinted with `RemoteViews.setColorStateList`;
  Android 8-11: no public API exists to tint a ProgressBar in RemoteViews, so the bar is white on the cover-coloured card.
- **Why the old card was grey:** `setColorized(true)` is honoured by Android only for foreground-service notifications (the id-1 notification) or
  media notifications with a MediaSession. The card was neither, so System UI drew its normal grey surface. The colour is now inside the card.
- **Foreground-service notification (id 1):** shows overall progress as the system's own tinted progress bar, and is colourized (the one place Android
  allows it) while exactly one download is active.
- **Coordinated palette (`CoverPalette.kt`, pure Kotlin, JVM-testable):** from the cover it derives surface, title colour, secondary text, progress fill,
  percentage colour, speed colour (the cover's second colour when it has one), empty track, button container, warning colour. Light covers
  (yellow, cyan, lime) get a light surface with dark text, others a dark surface with light text; mostly-black covers get a quiet, desaturated palette.
  Contrast: title >= 7:1, other text >= 4.5:1, bar fill >= 3:1. Light surfaces only on Android 12+ (the bar cannot be tinted below that).
  The analysis is cached per download and recomputed when the cover changes (signature: iconReady / titleId / title).
- Hue binning in `CoverColor.dominant` is now centred on 0 degrees, so red covers are no longer split into two half-weight bins.
- NOT compiled here either (no Android SDK): see the notes in the change report.

## 4.5
- Fix: the home screen showed "ezRemote web: Available" whenever ezRemote Server answered, even when ezRemote web (port 8080) was not reachable (the
  detail text below said so). The web status now only comes from a real check of port 8080 (Refresh, or opening the screen).

## 4.4 (downloads while a game is running)
- **Links are sent straight to ezRemote Server** (port 6701, `POST /v2/add`) when the custom server is running. Before, every download went through
  ezRemote Client's web server (port 8080); the PS4 suspends ezRemote Client while a game runs, so nothing could be added. The server is a payload and
  keeps answering. The original server, or a failure to reach the custom one, still uses the old route (8080).
- A finished download is **verified by the server's own report** when ezRemote web is not reachable (it already checks the HTTP status, that it is not a
  web page, and the final size), instead of waiting in "Checking downloaded file" until ezRemote is opened again.
- The home screen explains that "ezRemote web: unavailable" is normal while a game runs. Still needs ezRemote web: the Files tab, game covers/names
  of new downloads, and picking a free file name (while it is off, a new download with the same name as an existing file on the PS4 is not detected).
- NOT compiled by the author (no Android SDK here).

## 4.3 (music-player style notifications)
- **One card per download** (active or paused), styled like a music player: media-style notification tinted with the game's cover colour, the cover as
  the picture, the name, a progress bar made of blocks with the percentage, `downloaded / total` size, size left, time left and speed.
- **Two buttons:** Pause <-> Resume (it turns into Resume when the download is stopped, and the card stays so you can resume from it) and Delete.
  Delete asks for a second tap within 6 seconds (the card text changes and the icon turns into a warning); it then removes the download
  and its file from the PS4, the same as "delete with files" in the app.
- The foreground-service notification became one quiet line (new channel "Monitoring (service)", importance minimum); the details are in the cards.
- Finished / interrupted notifications are unchanged.
- NOT compiled by the author (no Android SDK here), like the rest of this project: expect to check the first build.

## 4.2 (custom server edition)
- **Server screen** (Settings > ezRemote Server): status (custom / original / unreachable), choose the server .elf once and send it to GoldHEN's BinLoader
  (TCP 9090) with one tap, server download settings (parallel downloads, automatic / fixed connections), server log (also read from the PS4 file
  when the server is down).
- **Self-healing:** expired links are refreshed automatically (the original link is sent again to the SAME file, up to 5 times in a row); if the
  server stops answering for 60 s while downloads are unfinished, the saved .elf is sent again (at most every 5 minutes).
- **Notifications:** one ongoing notification listing every active download (overall progress, speed, ETA), tinted with the game's cover colour;
  a heads-up alert when a download is interrupted (server not reachable 90 s / no progress 3 min; one per incident, removed when it recovers;
  setting "Alert me when a download is interrupted"); one grouped notification per finished download.
- **Cover colours:** cards, progress bars and the detail screen take the dominant colour of the game's cover.
- The custom server retries transient failures forever (only consecutive failures count), so downloads no longer stop and wait for "Resume".
