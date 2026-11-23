# OpenCollab privacy policy

*Effective 23 November 2026. Applies to the OpenCollab plugin for JetBrains IDEs and to the
OpenCollab relay server software.*

OpenCollab is made by chomusuke-png and hi444 ("we"). Contact: owokenji@gmail.com.

**In short:** OpenCollab has no servers of ours, no accounts, no analytics and no telemetry. We
receive nothing about you or your code. Your data goes only to the people you invite, to a relay
server that you or your team chose, and, only if you turn the matching options on, to your router
and to a public IP lookup service.

## What we collect

Nothing. The plugin sends no usage statistics, crash reports or identifiers to us or to anyone
else, and we run no service that receives your data.

## What the plugin sends, to whom, and when

| Data | Sent to | When |
|---|---|---|
| The shared project's files and edits, cursor positions and selections, chat messages, your display name, and, if the host shares them, run, build and test output, debugger state, breakpoints, version control status and code style | The people in your session (the host and the guests the host lets in) | Only while you host or join a session. Everything is encrypted with TLS; through a relay it stays end-to-end encrypted between host and guest |
| Your IP address | The host of a session you join (shown to the host partly hidden when you ask to join), the guests of a session you host (in the invitation), and the relay server if one is used | Only while you host or join a session |
| Connection metadata (IP addresses, connection times, a random room identifier). The relay never sees the contents, which are end-to-end encrypted | The relay server configured in *Settings \| Tools \| OpenCollab \| Relay*. The relay is software you or your team run; we do not run a public relay | Only while a session uses that relay |
| A request for this computer's public IP address (the service sees your public IP) | `https://api.ipify.org`, or the service you enter in *Port Forwarding \| Public IP service* | Only if you turn on manual *Port forwarding* and leave *Public address* empty, or press *Detect* |
| Requests to map one TCP port (UPnP IGD or NAT-PMP) | Your router, on your local network only | Only if you turn on *Automatically configure my router*, which is off by default and asks for confirmation |

When you click a link in the plugin (user guide, what's new), your browser opens a page on
GitHub, which then applies its own privacy policy. Builds of the plugin downloaded from GitHub
rather than from the JetBrains Marketplace also check GitHub for new versions, as the IDE does
for any plugin repository.

## What is stored on your computer

* The plugin's settings, in the IDE's configuration. The relay host secret is kept in the IDE's
  password storage.
* The shared project files, in the folder a guest chooses when joining.
* The session's activity log and chat, in memory only, until the IDE closes.
* Connection messages, with full addresses, in the IDE's own log file (`idea.log`), like other
  plugins' messages. The IDE manages and rotates that file.

Diagnostics for bug reports are only copied when you click *Copy Diagnostics*, and they hide
addresses and names. Nothing is sent: you decide what to paste and where.

## The relay server

The relay server software keeps no files except its own TLS certificate. It prints connection
information to its log, which the person running it controls. If you use someone else's relay,
that person's own policies apply.

## Your choices

* Port forwarding, automatic port forwarding and the relay are off by default.
* *Show IP addresses, host names and ports* (off by default) controls whether addresses are shown
  in full on screen.
* Leaving a session or ending it stops all sharing. Deleting the shared folder removes the copy
  of the project on a guest's computer.

## Children

OpenCollab is a developer tool and is not directed at children.

## Changes

Changes to this policy are listed in the [changelog](../CHANGELOG.md) and take effect with the
plugin version that includes them.
