# Phone Cleanup V5

Windows cleanup helper for the remote-phone project.

## Purpose
Run after using phone screenshots/files through Windows Mobile Devices / CrossDevice.

## Clears
- File Explorer Recent items
- Jump Lists / MRU history
- Explorer thumbnail/icon caches
- Local Phone Link / CrossDevice cache folders where present

## Intentionally does not touch
- Chrome cookies, passwords, browsing history, or login sessions
- Phone pairing with Windows
- Actual phone files
- Recycle Bin
- The live CrossDevice-mounted phone storage tree

## Usage
Double-click `Phone_Cleanup_V5_SAFE.bat` after a research session.

## Safety note
This is practical cleanup, not forensic-grade secure erasure. The script is intentionally conservative around the live CrossDevice-mounted phone path so it does not risk deleting files from the phone.
