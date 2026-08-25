# Thread Protection — Privacy Policy

**Effective August 25, 2026** · App version 2.4.1 · Package `com.threadprotection.app`

A styled, published version of this policy (ready to paste into Google Play Console's
"Privacy policy" field) lives at:
https://claude.ai/code/artifact/7f4d6c28-ebbb-4f96-b64f-fdad3c013371

This file is the plain-text source of the same content, kept in the repo for editing and for
hosting on your own domain if you'd rather not depend on the link above long-term.

## Overview

Thread Protection is a security app: it scans your device, watches for suspicious hardware,
checks links and websites, and (optionally) lets you chat with a nearby device over Bluetooth.
There is no company server behind any of this — the app has nowhere of its own to send your
data. What it does send goes to specific, named third-party services, only for the specific
check you asked for, and only while you're using that feature.

## Information we collect

**Account information.** You can use Thread Protection with a local account or with Google
Sign-In:
- *Local account* — the name, email and password you type in "Create an account." Your password
  is never stored as plain text: it's hashed with PBKDF2-HMAC-SHA256 (120,000 iterations, a
  unique random salt per account) before it ever touches storage, and the hash never leaves your
  device.
- *Google Sign-In* — if you sign in with Google, we receive your name, email address and profile
  picture from Google's own Credential Manager. We store it on-device to personalize the app; we
  don't use it for anything else.

**Device & security-scan information.** Running a scan or opening the audit screens reads
information directly from Android on your device — none of it is transmitted anywhere unless
the "Shared with third parties" section below says otherwise:
- Installed apps, their names, and which sensitive permissions each one holds
- Whether an app was installed from an app store or sideloaded
- Connected USB and Bluetooth hardware (device name and type)
- Open network ports on your device and which local app owns each one
- Device build details: manufacturer, model, Android version, security patch date, kernel version
- How recently an app last used a given permission (only if you've granted Usage Access)
- Which accessibility services are currently enabled

**Bluetooth Chat information.** Chat discovers nearby Bluetooth devices using Android's standard
Bluetooth APIs — device names and addresses are read locally to show you who's nearby, and are
not sent to us or anyone else. Messages you send are described under "Storage & security" below.

**Information you provide directly.** Any free API key you paste into Settings for a
threat-intelligence source, the URLs you check, and the text you send in a chat.

## Shared with third parties

Checking a link or an email address only works by asking someone who tracks that information —
a threat-intelligence database, a domain registry, a breach archive. Here is every outside
service Thread Protection can contact, exactly what it sends, and why. Each provider handles
that data under its own privacy policy.

| Service | What's sent | When | Used for |
|---|---|---|---|
| PhishTank | The URL you're checking | Every scan | Phishing-link detection |
| ipwho.is | The resolved IP address | Every scan | Hosting provider / ISP / country lookup |
| RDAP (rdap.org + the registry it redirects to) | The domain name | Every scan | Domain registration date, to flag brand-new domains |
| The site being checked | A normal connection request (same as opening it in a browser) | Every scan | Reading its TLS certificate and response headers directly |
| Google Safe Browsing | The URL you're checking | Only if you add your own free key | Malware & phishing verdicts |
| VirusTotal | The URL you're checking | Only if you add your own free key | Multi-engine reputation score |
| AbuseIPDB | The resolved IP address | Only if you add your own free key | Malicious-host reputation |
| URLhaus & ThreatFox (abuse.ch) | The URL / resolved host | Only if you add your own free key | Malware-distribution & IOC databases |
| NVD (NIST) | The text "Android WebView" (plus your key, if set) | During each device scan | Public CVE lookup for your WebView version |
| XposedOrNot | Your signed-in email address | When you open Data breach security | Known-breach lookup |

## What we don't collect

- **No advertising or analytics SDKs.** Nothing in this app profiles you, tracks you across
  other apps, or serves you ads.
- **No GPS or precise location is ever read.** Android requires the Location permission on
  versions before Android 12 purely to let Bluetooth discovery return device names — Thread
  Protection never reads your actual coordinates.
- **No chat content reaches us.** Messages travel directly, encrypted, from your device to the
  other device over Bluetooth — never through a server we operate, because none exists.
- **Nothing is ever sold.**

## Permissions explained

| Permission | Why Thread Protection asks for it |
|---|---|
| Camera | To scan QR codes. Decoding happens entirely on your device — no photo is stored or uploaded. |
| Internet | To run the checks in the table above, only when you use those features. |
| Bluetooth (scan / connect) | To find and connect to nearby devices for Chat, and to notice newly connected accessories for Hardware Watch. |
| Location (Android 11 and below) | Required by Android itself for Bluetooth discovery to work on older versions. Not read or used for anything else. |
| Query all packages | To list installed apps and their permissions for the security scan. |
| Notifications | To alert you about scan results, connected hardware, and scheduled-scan findings. |
| Foreground service | Keeps Hardware Watch running so you're still alerted after you close the app. |
| Receive boot completed | Resumes Hardware Watch after your phone restarts, if you left it on. |
| Quick Settings tile | Powers the optional App Permissions shortcut tile. |
| Usage Access | A special permission granted separately in system settings, so the app can show how recently a permission was actually used. |

## Storage & security

Account info, your theme choice, and any API keys you add live only in the app's private local
storage on your device (Android DataStore) — removed the moment you uninstall the app or clear
its data in system settings.

**Bluetooth Chat encryption.** Every conversation starts with a fresh ML-KEM-768 key exchange —
the NIST-standardized post-quantum algorithm (FIPS 203) — so a future quantum computer breaking
today's math doesn't expose it. Every message is then encrypted with AES-256-GCM. A new key is
generated for every connection, so compromising one conversation can't expose any other. Messages
exist only in memory while the app is open and are not written to disk.

Because there is no Thread Protection server, there's also nothing of ours to breach: your scan
results, account details and messages simply aren't somewhere a server-side incident could
expose them.

## Retention & deletion

- Scan results, permission audits and settings stay on your device until you clear the app's
  storage or uninstall it.
- Signing out removes your stored account association from the device.
- Chat messages are never retained past the current conversation.
- Uninstalling Thread Protection removes all of the above, immediately and completely.

## Children's privacy

Thread Protection is not directed at children under 13, and we don't knowingly collect
information from them. If you believe a child has provided personal information through this
app, contact us using the details below and we'll remove it.

## Your choices

- Turn off real-time protection, scheduled scans, or any other protection toggle anytime in
  Settings.
- Remove any threat-intel API key you've added, anytime, from the same screen.
- Revoke Camera, Bluetooth, Location or Notification access anytime via Android Settings → Apps
  → Thread Protection → Permissions.
- Revoke Usage Access via Settings → Apps → Special app access → Usage access.
- Uninstall the app to delete everything it stored, in one step.

For anything sent to a third party listed above, that provider's own privacy policy governs how
they handle it from there.

## Changes to this policy

If this policy changes, we'll update the effective date at the top of this page. If a future
version of the app starts collecting or sharing data in a materially different way, we'll
describe it here before that change ships.

## Contact

**Alok**
Developer, Thread Protection
professionawork@gmail.com
