# Beta testing OpenCollab

Thanks for helping! OpenCollab lets people edit one IntelliJ IDEA project together, each from
their own IDE. This page tells you how to install it, what to try and how to report what you
find. A session takes two people (or two IDEs on one computer); about 30 minutes is plenty.

## Install

You need IntelliJ IDEA **2026.2 or newer** (the free version is enough) on Windows, macOS or
Linux.

1. Download `OpenCollab-<version>.zip` from the
   [latest release](https://github.com/chomusuke-png/OpenCollab/releases/latest). Do not unzip it.
2. In IntelliJ: *Settings | Plugins | ⚙ (gear) | Install Plugin from Disk…*, pick the ZIP and
   restart if asked.
3. An **OpenCollab** tool window appears on the right. New versions install like any plugin
   update, so you only do this once.

## Connecting

Pick the way that fits where you both are:

| You are | Use |
|---|---|
| On the same network (home, office) | Nothing to set up: the host clicks **Share this project** |
| On different networks, the host can open a port on their router | Host: *Settings \| Tools \| OpenCollab \| Port forwarding* |
| On different networks, with a relay | Host: *Settings \| Tools \| OpenCollab \| Relay* (URL, secret and maybe a fingerprint from whoever runs it) |
| On the same VPN (Tailscale, ZeroTier, …) | Like the same network |

Then the host clicks **Invite ▾ > Copy editor invitation** and sends it to the guest privately
(a direct message, not a public channel). The guest opens an **empty folder** as a project,
clicks **Join with an invitation** and pastes it. The host gets a request and clicks **Let in**.

The [user guide](USER_GUIDE.md) explains every option in detail.

## What to try

Do whatever you would normally do, and also try a few of these. Swap host and guest halfway
through if you can.

**Editing together**
- [ ] Both type in the same file at the same time, also on the same line.
- [ ] Create, rename, move and delete files and folders, from either side.
- [ ] Undo and redo, find and replace, reformat code, refactor (rename a class used in many files).
- [ ] Click a name in the participant list and choose *Follow*; follow the other person around.

**Running on the host**
- [ ] Guest: **Run on host ▾** > a configuration, or *Build project*. Host: allow it. Does the
      output appear for the guest? Run a program that reads input or never ends; stop it.
- [ ] Run tests on the host: the guest should see the test tree; double-click a failed test.
- [ ] Debug on the host with a breakpoint: the guest should see where it stopped and the variables.
- [ ] Guest: ask to run a terminal command (it needs the host's approval every time).

**People and permissions**
- [ ] Invite a viewer: they can read and chat, but not edit.
- [ ] Right-click a participant: change their role, take away a permission, remove them.
- [ ] Leave and join again: the host's restrictions should still apply.
- [ ] Chat, including while the chat tab is hidden.
- [ ] The host ends the session: guests should see that it ended, not keep reconnecting.

**When things go wrong**
- [ ] Turn the guest's Wi-Fi off for 10 seconds, then on: it should reconnect and catch up.
- [ ] Edit while disconnected, then reconnect.
- [ ] Try a large project, large files, or a slow connection.
- [ ] Streaming or sharing your screen? Check that no full IP address appears anywhere.

## Reporting

Open an issue: [bug report](https://github.com/chomusuke-png/OpenCollab/issues/new?template=bug_report.yml)
for something broken, [idea or feedback](https://github.com/chomusuke-png/OpenCollab/issues/new?template=feedback.yml)
for anything else, including "this was confusing" — that helps as much as a crash.

For a bug, attach the **diagnostics**: in the OpenCollab tool window, *Activity* tab, click the
copy icon (or *Tools | OpenCollab | Copy Diagnostics*), and paste them into the report. They
contain the versions, the connection settings and the recent activity, with IP addresses, your
user folder, the project path and the participants' names hidden. Read them before posting: a
project or file name you would rather not share could still appear. The other person's
diagnostics help too.

Never post invitations or relay secrets: issues are public. Report security problems privately
(see [SECURITY.md](SECURITY.md#reporting-a-security-problem)).

## Good to know

- Everything runs on the host's computer: guests edit the host's project, and Run, tests and
  the debugger run there. Only invite people you trust with your code: editors can change files.
- A guest's folder is a copy of the host's project, kept in sync. Keep it for the next session or
  delete it; it is not used for anything else.
- Each IDE can be in one session at a time.
