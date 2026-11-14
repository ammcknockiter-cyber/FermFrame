# Putting FermFrame on GitHub

No tools needed – everything is done on the GitHub website.

## First time

1. **Make the project.** Sign in at github.com → **+** (top right) → **New repository**.
   Name: `FermFrame`. Public. Don't tick "Add a README" (we have one). Click **Create repository**.

2. **Tell the app where it lives.** In `manager\`, create two one-line text files
   (replace `YOUR-NAME` with your GitHub user name):
   - `project-url.txt` → `https://github.com/YOUR-NAME/FermFrame`
   - `shops-url.txt` → `https://raw.githubusercontent.com/YOUR-NAME/FermFrame/main/manager/shops.json`

   Then rebuild: `powershell -ExecutionPolicy Bypass -File manager\build.ps1 -Release`

3. **Gather the files:** `powershell -ExecutionPolicy Bypass -File manager\prepare-github.ps1`
   This puts exactly the files to publish in `dist\github-upload` (your RaptHooker backup, build leftovers
   and the old installer zip are left out).

4. **Upload them.** On the new repository page click **uploading an existing file**, open
   `dist\github-upload` in File Explorer, select everything inside it (Ctrl+A) and drag it onto the page.
   Write "First release" as the message and click **Commit changes**.

5. **Publish the download.** On the repository page: **Releases → Create a new release**.
   - Tag: `v1.0.0` (click "Create new tag")
   - Title: `FermFrame 1.0.0`
   - Description: paste `docs\release-notes-1.0.0.md`
   - Drag in `dist\FermFrame-1.0.0.exe`
   - Click **Publish release**

Share the link to the Releases page (`https://github.com/YOUR-NAME/FermFrame/releases`).

## Later releases

1. Bump the version: `AppInfo.Version` and the three assembly versions at the top of
   `manager\FermFrameApp.cs` (and `FERMFRAME_VERSION` in `platformio.ini` if the firmware changed).
2. Rebuild with `build.ps1 -Release`, run `prepare-github.ps1`, upload the changed files, and create a new release with the new
   `dist\FermFrame-<version>.exe`.

People who already have the app just download the new one and press **Update**.

## Changing the shop links

Edit `manager/shops.json` directly on GitHub (pencil icon) and commit. Apps built with `shops-url.txt`
pick up the change the next time someone opens **Buy another FermFrame** – no new release needed.

## Optional: no more "Windows protected your PC"

That warning appears because the app isn't signed. A code-signing certificate removes it over time, but costs
money every year; for a free hobby project most people just explain "More info → Run anyway", as the README does.
