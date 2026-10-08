# CAB-32 emulator acceptance plan

Use isolated `app.cablegram.phone.cab32` on Pixel_9 (`emulator-5556`, API 37) and
`app.cablegram.cab32` on CG_TV_1080p (`emulator-5554`, API 36). Local disposable account/API at port 3288;
synthetic three-second MP4s under Download, including an unselected control and a nested chosen folder.
Existing emulator app installations and accounts must remain intact. Work directly without delegation.

1. Audit both merged release manifests, including dependency permissions and their origins. Broad
   media/storage permissions must be absent. Review remaining dangerous/special permissions in context.
2. Phone: cancel the file picker, then choose only SelectedClip.mp4. Import should work without a
   media permission prompt; the unselected control must not be imported.
3. Choose CAB32Folder through the system folder picker, grant access, browse Nested and import
   FolderClip.mp4. Return to Import and open the remembered folder after app/process restart.
4. Verify persisted URI grants and actual selected file readability after restart. Revoke a folder
   grant, reopen the remembered folder, and assert a recoverable reselect message. Regrant and reopen.
5. TV: launch the isolated app and verify basic startup; permission audit is the TV acceptance scope.
   This ticket does not claim new playback evidence.
6. Run phone/TV unit suites, phone build, Telegram sync, and whitespace checks.

PASS requires observed UI behavior plus persisted/readable selected grants, not only Maestro success.
FAIL is a violated valid expectation. Missing observations or infrastructure failures are INCONCLUSIVE.
Zero blind retries. Stop and inspect unexpected UI; make a bounded corrective run only after identifying
an app, selector or setup error and record the failed attempt. Each flow has a 60-second observation limit.
Save flows, manifest audit and a result summary alongside this plan. No real-device coverage is claimed.

## Multiple-folder follow-up

On the same isolated phone package, add CAB32SecondFolder alongside CAB32Folder, then restart the
application and verify both appear and open. Reselect a folder and verify no duplicate. Cancel removal
and verify no change; confirm removal of the second folder and restart. Verify the first still opens.
Remove/reselect CAB32Folder to exercise a folder with an imported video. Native instrumentation must
confirm folder counts (including the existing separately selected Nested test folder), readable remaining grants, absence of the removed grant and denial of listing
the removed tree, while the two imported library entries remain. No blind retries; inspect failures
and save corrective evidence separately. Each UI flow has a 60-second observation limit.
